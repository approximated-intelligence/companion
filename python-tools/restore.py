#!/usr/bin/env python3
"""
restore.py — restore from B2 NaCl backup.

Supports two encryption formats:
  - Legacy (packs/ prefix): crypto_box_seal — no sender authentication
  - Current (<phone_pk_hex>/ prefix): crypto_box — authenticated sender

Handles B2 versioned buckets: when decryption fails on the latest version,
tries older versions of the same key.

Downloads are cached locally per bucket under ~/.cache/backup_decrypt/<bucket>/

Usage:
    uv run restore.py --list
    uv run restore.py --restore --output ~/restored/
    uv run restore.py --restore --output ~/restored/ --dump-pax /tmp/pax/
    uv run restore.py --restore --output ~/restored/ --no-cache
    uv run restore.py --restore --output ~/restored/ --offline
    uv run restore.py --restore --output ~/restored/ --prefix <phone_pk_hex>
"""

import argparse
import ctypes
import ctypes.util
import hashlib
import json
import logging
import os
import sys
import tempfile
from dataclasses import dataclass, field
from getpass import getpass
from pathlib import Path
from typing import Generator, Iterator

import boto3

from passphrase_utils import validate_passphrase

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# libsodium FFI
# ---------------------------------------------------------------------------

_lib = ctypes.CDLL(ctypes.util.find_library("sodium") or "libsodium.so")
_lib.sodium_init()

_PAX_BLOCK = 512
_PAX2_MAGIC = b"PX2\n"

SEALBYTES = 16 + 32  # crypto_box_seal overhead
BOX_MACBYTES = 16     # crypto_box MAC
BOX_NONCEBYTES = 24   # crypto_box nonce

_LEGACY_PART_SIZE = 8 * 1024 * 1024
_PART_PAX_CAPACITY = _LEGACY_PART_SIZE - _PAX_BLOCK

_LEGACY_PREFIX = "packs"


# ---------------------------------------------------------------------------
# Crypto
# ---------------------------------------------------------------------------


def decrypt_seal(cipher_chunk: bytes, secret_key: bytes, public_key: bytes) -> bytes:
    """Decrypt a crypto_box_seal part (legacy). Returns plaintext."""
    plain_buf = ctypes.create_string_buffer(len(cipher_chunk))
    rc = _lib.crypto_box_seal_open(
        plain_buf,
        cipher_chunk,
        ctypes.c_ulonglong(len(cipher_chunk)),
        public_key,
        secret_key,
    )
    if rc != 0:
        raise RuntimeError("crypto_box_seal_open failed")
    return plain_buf.raw[: len(cipher_chunk) - SEALBYTES]


def decrypt_box(wire: bytes, sender_pk: bytes, recipient_sk: bytes) -> bytes:
    """Decrypt a crypto_box part (current). Wire = [24-byte nonce][ciphertext].
    Returns plaintext."""
    if len(wire) <= BOX_NONCEBYTES + BOX_MACBYTES:
        raise RuntimeError(f"Wire too short: {len(wire)}")

    nonce = wire[:BOX_NONCEBYTES]
    ciphertext = wire[BOX_NONCEBYTES:]
    plaintext_len = len(ciphertext) - BOX_MACBYTES
    plain_buf = ctypes.create_string_buffer(plaintext_len)

    rc = _lib.crypto_box_open_easy(
        plain_buf,
        ciphertext,
        ctypes.c_ulonglong(len(ciphertext)),
        nonce,
        sender_pk,
        recipient_sk,
    )
    if rc != 0:
        raise RuntimeError("crypto_box_open_easy failed")
    return plain_buf.raw[:plaintext_len]


def decrypt_part(
    wire: bytes,
    prefix: str,
    server_sk: bytes,
    server_pk: bytes,
    sender_pk: bytes | None,
) -> bytes:
    """Decrypt a part using the appropriate method based on prefix."""
    if prefix == _LEGACY_PREFIX:
        return decrypt_seal(wire, server_sk, server_pk)
    else:
        if sender_pk is None:
            raise RuntimeError("sender_pk required for crypto_box decryption")
        return decrypt_box(wire, sender_pk, server_sk)


# ---------------------------------------------------------------------------
# PAX2 parsing (pure)
# ---------------------------------------------------------------------------


def parse_pax2_records(block: bytes) -> dict[str, str]:
    if block[:4] != _PAX2_MAGIC:
        raise ValueError(f"Bad PAX2 magic: {block[:4]!r}")
    text = block[4:].decode("utf-8", errors="replace")
    records: dict[str, str] = {}
    pos = 0
    while pos < len(text):
        if text[pos] == "\x00":
            break
        space = text.find(" ", pos)
        if space < 0:
            break
        try:
            length = int(text[pos:space])
        except ValueError:
            break
        record = text[pos : pos + length]
        pos += length
        after_space = record[record.index(" ") + 1 :]
        eq = after_space.index("=")
        key = after_space[:eq]
        value = after_space[eq + 1 :].rstrip("\n")
        records[key] = value
    return records


def round_up_block(size: int) -> int:
    return ((size + _PAX_BLOCK - 1) // _PAX_BLOCK) * _PAX_BLOCK


def is_pad_entry(records: dict[str, str]) -> bool:
    """Pad block: no path, zero sha256."""
    return "path" not in records and records.get("sha256") == "0" * 64


# ---------------------------------------------------------------------------
# PAX entry extraction (pure)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PaxEntry:
    path: str
    mtime: int
    sha256: str
    real_size: int
    file_offset: int
    data: bytes


def extract_entries(buf: bytes, is_pack_end: bool) -> tuple[list[PaxEntry], bytes]:
    """Extract complete PAX2 entries from buffer.
    Returns (entries, remaining_buffer).
    When is_pack_end is True, the pack boundary delimits the last chunk."""
    entries: list[PaxEntry] = []
    pos = 0

    while pos + _PAX_BLOCK <= len(buf):
        block = buf[pos : pos + _PAX_BLOCK]

        if block[:4] != _PAX2_MAGIC:
            if is_pack_end:
                break
            log.debug("  pos=%d buf_len=%d block=%r", pos, len(buf), block[:16])
            raise ValueError(f"Expected PAX2 header, got {block[:4]!r}")

        records = parse_pax2_records(block)

        # Pad block: skip past random padding data
        if is_pad_entry(records):
            pad_size = int(records.get("realsize", "0"))
            log.debug("  pad: %d bytes", pad_size)
            pos += _PAX_BLOCK + pad_size
            continue

        path = records["path"]
        mtime = int(records["mtime"])
        real_size = int(records["realsize"])
        file_offset = int(records["offset"])
        sha256 = records["sha256"]

        chunk_data_len = min(real_size - file_offset, len(buf) - pos - _PAX_BLOCK)
        padded_len = round_up_block(chunk_data_len)

        log.debug(
            "  entry: path=%r real_size=%d offset=%d chunk=%d padded=%d pos=%d buf=%d",
            path, real_size, file_offset, chunk_data_len, padded_len, pos, len(buf),
        )

        if not is_pack_end and len(buf) - pos < _PAX_BLOCK + padded_len:
            break

        file_data = buf[pos + _PAX_BLOCK : pos + _PAX_BLOCK + chunk_data_len]
        pos += _PAX_BLOCK + padded_len

        entries.append(
            PaxEntry(
                path=path,
                mtime=mtime,
                real_size=real_size,
                file_offset=file_offset,
                sha256=sha256,
                data=file_data,
            )
        )

    return entries, buf[pos:]


# ---------------------------------------------------------------------------
# File accumulation actions (pure)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class FileAccum:
    path: str
    mtime: int
    sha256: str
    real_size: int
    offset: int


@dataclass(frozen=True)
class StartFile:
    path: str
    sha256: str
    real_size: int
    data: bytes


@dataclass(frozen=True)
class WriteChunk:
    data: bytes


@dataclass(frozen=True)
class FinishFile:
    path: str
    mtime: int
    sha256: str
    real_size: int


@dataclass(frozen=True)
class DiscardFile:
    path: str
    reason: str


def apply_entry(
    accum: FileAccum | None,
    entry: PaxEntry,
) -> tuple[FileAccum | None, list]:
    """Pure state transition. Returns (new_accum, actions)."""
    actions = []

    if entry.file_offset == 0:
        if accum is not None:
            actions.append(DiscardFile(accum.path, f"interrupted by {entry.path}"))

        actions.append(
            StartFile(
                entry.path,
                entry.sha256,
                entry.real_size,
                entry.data,
            )
        )
        new_offset = len(entry.data)

        if new_offset >= entry.real_size:
            actions.append(
                FinishFile(entry.path, entry.mtime, entry.sha256, entry.real_size)
            )
            return None, actions

        return (
            FileAccum(
                path=entry.path,
                mtime=entry.mtime,
                sha256=entry.sha256,
                real_size=entry.real_size,
                offset=new_offset,
            ),
            actions,
        )

    # continuation
    if (
        accum is None
        or accum.sha256 != entry.sha256
        or accum.offset != entry.file_offset
    ):
        if accum is not None:
            actions.append(
                DiscardFile(
                    accum.path,
                    f"expected offset {accum.offset}, got {entry.path} at {entry.file_offset}",
                )
            )
        return None, actions

    actions.append(WriteChunk(entry.data))
    new_offset = accum.offset + len(entry.data)

    if new_offset >= accum.real_size:
        actions.append(
            FinishFile(accum.path, accum.mtime, accum.sha256, accum.real_size)
        )
        return None, actions

    return (
        FileAccum(
            path=accum.path,
            mtime=accum.mtime,
            sha256=accum.sha256,
            real_size=accum.real_size,
            offset=new_offset,
        ),
        actions,
    )


# ---------------------------------------------------------------------------
# Plaintext chunk stream
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PlaintextChunk:
    pack_key: str
    pack_index: int
    part_number: int
    is_last_part: bool
    data: bytes


def iter_plaintext_chunks(
    s3,
    bucket: str,
    versions_by_key: dict[str, list[dict]],
    server_sk: bytes,
    server_pk: bytes,
    prefix: str,
    sender_pk: bytes | None,
    use_cache: bool,
    offline: bool,
    skip_packs: set[str] | None = None,
) -> Generator[PlaintextChunk, None, None]:
    """Yield decrypted plaintext chunks, one per sealed part."""
    all_keys = sorted(versions_by_key.keys())

    for pack_index, key in enumerate(all_keys):
        if skip_packs and key in skip_packs:
            log.debug("Skipping completed pack %s", key)
            continue

        versions = versions_by_key[key]
        ct = _fetch_any_version(s3, bucket, key, versions, use_cache, offline)
        if ct is None:
            continue

        num_parts = (len(ct) + _LEGACY_PART_SIZE - 1) // _LEGACY_PART_SIZE
        pos = 0
        part_num = 0

        while pos < len(ct):
            part_wire = min(_LEGACY_PART_SIZE, len(ct) - pos)
            plain = decrypt_part(
                ct[pos : pos + part_wire],
                prefix,
                server_sk,
                server_pk,
                sender_pk,
            )
            plain = plain[:_PART_PAX_CAPACITY]
            part_num += 1
            pos += part_wire

            log.debug(
                "  chunk: key=%r part=%d/%d is_last=%s plain_len=%d",
                key, part_num, num_parts, part_num == num_parts, len(plain),
            )

            yield PlaintextChunk(
                pack_key=key,
                pack_index=pack_index,
                part_number=part_num,
                is_last_part=(part_num == num_parts),
                data=plain,
            )


# ---------------------------------------------------------------------------
# Restore state persistence
# ---------------------------------------------------------------------------


@dataclass
class InflightFile:
    path: str
    sha256: str
    real_size: int
    offset: int
    tmp_path: str
    start_pack: str


@dataclass
class RestoreState:
    completed_packs: set[str] = field(default_factory=set)
    inflight: InflightFile | None = None

    def save(self, path: Path) -> None:
        data = {
            "completed_packs": sorted(self.completed_packs),
        }
        if self.inflight:
            data["inflight"] = {
                "path": self.inflight.path,
                "sha256": self.inflight.sha256,
                "real_size": self.inflight.real_size,
                "offset": self.inflight.offset,
                "tmp_path": self.inflight.tmp_path,
                "start_pack": self.inflight.start_pack,
            }
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2))
        tmp.rename(path)

    @staticmethod
    def load(path: Path) -> "RestoreState":
        if not path.exists():
            return RestoreState()
        data = json.loads(path.read_text())
        inflight = None
        if "inflight" in data:
            inf = data["inflight"]
            inflight = InflightFile(
                path=inf["path"],
                sha256=inf["sha256"],
                real_size=inf["real_size"],
                offset=inf["offset"],
                tmp_path=inf["tmp_path"],
                start_pack=inf["start_pack"],
            )
        return RestoreState(
            completed_packs=set(data.get("completed_packs", [])),
            inflight=inflight,
        )

    def validate_inflight(self) -> bool:
        if self.inflight is None:
            return True
        tmp = Path(self.inflight.tmp_path)
        if not tmp.exists():
            log.warning("Inflight temp file missing: %s", self.inflight.tmp_path)
            return False
        actual_size = tmp.stat().st_size
        if actual_size != self.inflight.offset:
            log.warning(
                "Inflight temp file size mismatch: expected %d, got %d",
                self.inflight.offset, actual_size,
            )
            return False
        return True

    def discard_inflight(self) -> str | None:
        if self.inflight is None:
            return None
        start_pack = self.inflight.start_pack
        Path(self.inflight.tmp_path).unlink(missing_ok=True)
        self.completed_packs.discard(start_pack)
        self.inflight = None
        return start_pack


# ---------------------------------------------------------------------------
# Restore consumer (I/O at the boundary)
# ---------------------------------------------------------------------------


def discard_tmp(tmp_name: str | None) -> None:
    if tmp_name:
        Path(tmp_name).unlink(missing_ok=True)


def restore_from_chunks(
    chunks: Iterator[PlaintextChunk],
    output: Path,
    dump_pax: Path | None,
    state: RestoreState,
    state_path: Path,
) -> tuple[int, list[str]]:
    """Consume plaintext chunks, extract files to output. Returns (count, warnings)."""
    output.mkdir(parents=True, exist_ok=True)
    if dump_pax:
        dump_pax.mkdir(parents=True, exist_ok=True)

    warnings: list[str] = []
    file_count = 0
    buf = b""
    accum: FileAccum | None = None

    cur_tmp_fh = None
    cur_tmp_name: str | None = None
    cur_digest = None
    cur_start_pack: str | None = None

    # Resume inflight file if valid
    if state.inflight and state.validate_inflight():
        inf = state.inflight
        log.info("Resuming inflight file: %s (%d/%d bytes)", inf.path, inf.offset, inf.real_size)
        accum = FileAccum(
            path=inf.path,
            mtime=0,
            sha256=inf.sha256,
            real_size=inf.real_size,
            offset=inf.offset,
        )
        cur_tmp_fh = open(inf.tmp_path, "ab")
        cur_tmp_name = inf.tmp_path
        cur_digest = hashlib.sha256()
        with open(inf.tmp_path, "rb") as f:
            while True:
                block = f.read(64 * 1024)
                if not block:
                    break
                cur_digest.update(block)
        cur_start_pack = inf.start_pack
    elif state.inflight:
        reprocess = state.discard_inflight()
        if reprocess:
            log.info("Discarding invalid inflight, will reprocess from %s", reprocess)

    pack_dump = bytearray() if dump_pax else None
    current_pack_key: str | None = None

    def execute(action):
        nonlocal cur_tmp_fh, cur_tmp_name, cur_digest, cur_start_pack, file_count

        if isinstance(action, StartFile):
            cur_tmp_fh = tempfile.NamedTemporaryFile(delete=False, dir=output)
            cur_tmp_name = cur_tmp_fh.name
            cur_digest = hashlib.sha256()
            cur_start_pack = current_pack_key
            cur_tmp_fh.write(action.data)
            cur_digest.update(action.data)

        elif isinstance(action, WriteChunk):
            cur_tmp_fh.write(action.data)
            cur_digest.update(action.data)

        elif isinstance(action, FinishFile):
            cur_tmp_fh.close()
            actual = cur_digest.hexdigest()
            if actual != action.sha256:
                discard_tmp(cur_tmp_name)
                cur_tmp_fh = None
                cur_tmp_name = None
                cur_digest = None
                cur_start_pack = None
                raise ValueError(
                    f"sha256 mismatch for {action.path}: "
                    f"expected {action.sha256}, got {actual}"
                )
            dest = output / action.path
            dest.parent.mkdir(parents=True, exist_ok=True)
            Path(cur_tmp_name).rename(dest)
            mtime_s = action.mtime / 1000
            os.utime(dest, (mtime_s, mtime_s))
            file_count += 1
            log.info("Restored %s (%d bytes)", action.path, action.real_size)
            cur_tmp_fh = None
            cur_tmp_name = None
            cur_digest = None
            cur_start_pack = None
            state.inflight = None

        elif isinstance(action, DiscardFile):
            if cur_tmp_fh:
                cur_tmp_fh.close()
            discard_tmp(cur_tmp_name)
            warnings.append(f"Discarding {action.path}: {action.reason}")
            cur_tmp_fh = None
            cur_tmp_name = None
            cur_digest = None
            cur_start_pack = None
            state.inflight = None

    def drain(is_pack_end: bool):
        nonlocal buf, accum
        entries, buf = extract_entries(buf, is_pack_end)
        for entry in entries:
            if accum is not None and accum.mtime == 0 and entry.sha256 == accum.sha256:
                accum = FileAccum(
                    path=accum.path,
                    mtime=entry.mtime,
                    sha256=accum.sha256,
                    real_size=accum.real_size,
                    offset=accum.offset,
                )
            accum, actions = apply_entry(accum, entry)
            for action in actions:
                execute(action)
        if is_pack_end and buf:
            if buf != b"\x00" * len(buf):
                warnings.append(f"Skipped {len(buf)} bytes of non-PAX data at end of {current_pack_key}")
            buf = b""

    def save_progress():
        if accum is not None and cur_tmp_name is not None:
            if cur_tmp_fh:
                cur_tmp_fh.flush()
            state.inflight = InflightFile(
                path=accum.path,
                sha256=accum.sha256,
                real_size=accum.real_size,
                offset=accum.offset,
                tmp_path=cur_tmp_name,
                start_pack=cur_start_pack or current_pack_key,
            )
        else:
            state.inflight = None
        state.save(state_path)

    for chunk in chunks:
        if current_pack_key is not None and chunk.pack_key != current_pack_key:
            drain(is_pack_end=True)
            if dump_pax and pack_dump:
                (dump_pax / current_pack_key.replace("/", "_")).write_bytes(pack_dump)
            pack_dump = bytearray() if dump_pax else None
            state.completed_packs.add(current_pack_key)
            save_progress()

        current_pack_key = chunk.pack_key
        buf = buf + chunk.data
        if pack_dump is not None:
            pack_dump.extend(chunk.data)

        if chunk.is_last_part:
            drain(is_pack_end=True)
            if dump_pax and pack_dump:
                (dump_pax / chunk.pack_key.replace("/", "_")).write_bytes(pack_dump)
            pack_dump = bytearray() if dump_pax else None
            state.completed_packs.add(current_pack_key)
            save_progress()
        else:
            drain(is_pack_end=False)

    if current_pack_key and current_pack_key not in state.completed_packs:
        state.completed_packs.add(current_pack_key)
        save_progress()

    # Incomplete file at end — save as inflight for next run
    if accum is not None:
        if cur_tmp_fh:
            cur_tmp_fh.flush()
            cur_tmp_fh.close()
        if cur_tmp_name:
            state.inflight = InflightFile(
                path=accum.path,
                sha256=accum.sha256,
                real_size=accum.real_size,
                offset=accum.offset,
                tmp_path=cur_tmp_name,
                start_pack=cur_start_pack or current_pack_key,
            )
            log.info(
                "Inflight: %s (%d/%d bytes) — will resume on next run",
                accum.path, accum.offset, accum.real_size,
            )
        else:
            state.inflight = None
        state.save(state_path)

    return file_count, warnings


# ---------------------------------------------------------------------------
# B2 client with per-bucket disk cache + version support
# ---------------------------------------------------------------------------


def b2_client(cfg: dict):
    key_id = cfg.get("b2_restore_key_id") or cfg.get("b2_rw_key_id")
    app_key = cfg.get("b2_restore_application_key") or cfg.get("b2_rw_application_key")
    if not key_id or not app_key:
        raise RuntimeError("No B2 credentials in config")
    return boto3.client(
        "s3",
        endpoint_url=cfg["b2_endpoint"],
        aws_access_key_id=key_id,
        aws_secret_access_key=app_key,
    )


def _cache_dir(bucket: str) -> Path:
    return Path.home() / ".cache" / "backup_decrypt" / bucket


def b2_list_pack_versions(s3, bucket: str, prefix: str) -> dict[str, list[dict]]:
    """List all versions of pack keys under prefix. Returns {key: [versions newest first]}."""
    versions_by_key: dict[str, list[dict]] = {}
    paginator = s3.get_paginator("list_object_versions")
    for page in paginator.paginate(Bucket=bucket, Prefix=f"{prefix}/"):
        for v in page.get("Versions", []):
            key = v["Key"]
            versions_by_key.setdefault(key, []).append(v)
    for key in versions_by_key:
        versions_by_key[key].sort(key=lambda v: v["LastModified"], reverse=True)
    return versions_by_key


def b2_get_cached(
    s3,
    bucket: str,
    key: str,
    size: int,
    use_cache: bool,
    version_id: str | None = None,
) -> tuple[bytes, bool]:
    cache_suffix = f"_v{version_id}" if version_id else ""
    cache_name = key.replace("/", "_") + cache_suffix

    if use_cache:
        cache = _cache_dir(bucket)
        cached_file = cache / cache_name
        if cached_file.exists() and (size == 0 or cached_file.stat().st_size == size):
            return cached_file.read_bytes(), True

    if version_id:
        data = s3.get_object(Bucket=bucket, Key=key, VersionId=version_id)[
            "Body"
        ].read()
    else:
        data = s3.get_object(Bucket=bucket, Key=key)["Body"].read()

    if use_cache:
        cache = _cache_dir(bucket)
        cached_file = cache / cache_name
        cache.mkdir(parents=True, exist_ok=True)
        tmp = cached_file.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.rename(cached_file)

    return data, False


def _fetch_any_version(s3, bucket, key, versions, use_cache, offline) -> bytes | None:
    for vi, ver in enumerate(versions):
        vid = ver["VersionId"]
        size = ver.get("Size", 0)

        if offline and use_cache:
            cache_name = key.replace("/", "_") + f"_v{vid}"
            cached_file = _cache_dir(bucket) / cache_name
            if cached_file.exists():
                return cached_file.read_bytes()
            if vi == 0:
                unversioned = _cache_dir(bucket) / key.replace("/", "_")
                if unversioned.exists():
                    return unversioned.read_bytes()
            continue

        try:
            ct, _ = b2_get_cached(s3, bucket, key, size, use_cache, vid)
            return ct
        except Exception as e:
            log.warning("  %s v%d DOWNLOAD FAILED: %s", key, vi + 1, e)
            continue

    return None


# ---------------------------------------------------------------------------
# Prefix discovery
# ---------------------------------------------------------------------------


def discover_prefixes(s3, bucket: str) -> list[str]:
    """List distinct prefixes (device public keys or 'packs') in bucket."""
    prefixes = set()
    paginator = s3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Delimiter="/"):
        for cp in page.get("CommonPrefixes", []):
            prefix = cp["Prefix"].rstrip("/")
            prefixes.add(prefix)
    return sorted(prefixes)


def classify_prefix(prefix: str) -> str:
    """Return 'legacy' for packs/ or 'box' for phone public key prefixes."""
    if prefix == _LEGACY_PREFIX:
        return "legacy"
    if len(prefix) == 64 and all(c in "0123456789abcdef" for c in prefix):
        return "box"
    return "unknown"


def sender_pk_from_prefix(prefix: str) -> bytes:
    """Decode phone public key from hex prefix."""
    return bytes.fromhex(prefix)


# ---------------------------------------------------------------------------
# List command
# ---------------------------------------------------------------------------


def cmd_list(s3, bucket: str, prefix: str | None) -> None:
    if prefix is None:
        prefixes = discover_prefixes(s3, bucket)
        if not prefixes:
            print("No data found in bucket.")
            return
        print(f"Found {len(prefixes)} prefix(es):")
        for p in prefixes:
            kind = classify_prefix(p)
            label = f"({kind})" if kind != "unknown" else "(unknown format)"
            print(f"  {p}  {label}")
        print("\nUse --prefix to list packs for a specific device.")
        return

    packs = []
    for page in s3.get_paginator("list_objects_v2").paginate(
        Bucket=bucket,
        Prefix=f"{prefix}/",
    ):
        packs.extend(page.get("Contents", []))
    packs.sort(key=lambda o: o["Key"])

    total = sum(o.get("Size", 0) for o in packs)
    kind = classify_prefix(prefix)
    print(f"Prefix: {prefix}  ({kind})")
    print(f"{'Key':<50} {'MB':>8}  {'Date'}")
    print("\u2500" * 75)
    for o in packs:
        print(
            f"  {o['Key']:<48} {o['Size']/1024/1024:>7.1f}  "
            f"{o['LastModified']:%Y-%m-%d %H:%M}"
        )
    print(f"\n{len(packs)} packs  {total/1024/1024:.1f} MB total ciphertext")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def load_config(path: str) -> dict:
    return json.loads(Path(path).read_text())


def main() -> None:
    p = argparse.ArgumentParser(
        description="Restore from B2 NaCl backup (PAX2, crypto_box_seal or crypto_box)",
    )
    p.add_argument("--config", default="config.json")
    p.add_argument("--output", default="./restored")
    p.add_argument("--list", action="store_true")
    p.add_argument("--restore", action="store_true")
    p.add_argument("--prefix", help="Device prefix (phone public key hex or 'packs' for legacy)")
    p.add_argument("--no-cache", action="store_true", help="Disable download cache")
    p.add_argument("--offline", action="store_true", help="Only use cached packs")
    p.add_argument("--fresh", action="store_true", help="Ignore saved restore state")
    p.add_argument(
        "--dump-pax", metavar="DIR", help="Write decrypted plaintext per pack"
    )
    p.add_argument(
        "-v", "--verbose", action="store_true", help="Enable debug logging"
    )
    args = p.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s: %(message)s",
    )

    cfg = load_config(args.config)
    s3 = b2_client(cfg)
    bucket = cfg["b2_bucket"]
    output = Path(args.output).expanduser().resolve()
    use_cache = not args.no_cache
    dump_pax = Path(args.dump_pax).expanduser().resolve() if args.dump_pax else None

    if args.list:
        cmd_list(s3, bucket, args.prefix)
        return

    if not args.restore:
        p.print_help()
        return

    # Discover or validate prefix
    prefix = args.prefix
    if prefix is None:
        prefixes = discover_prefixes(s3, bucket)
        restorable = [p for p in prefixes if classify_prefix(p) != "unknown"]
        if not restorable:
            print("ERROR: No restorable prefixes found in bucket.", file=sys.stderr)
            sys.exit(1)
        if len(restorable) == 1:
            prefix = restorable[0]
            print(f"Auto-selected prefix: {prefix} ({classify_prefix(prefix)})")
        else:
            print("Multiple devices found. Select with --prefix:")
            for p in restorable:
                print(f"  {p}  ({classify_prefix(p)})")
            sys.exit(1)

    kind = classify_prefix(prefix)
    if kind == "unknown":
        print(f"ERROR: Unrecognised prefix format: {prefix}", file=sys.stderr)
        sys.exit(1)

    expected_pub = cfg.get("nacl_public_key_hex")
    if not expected_pub:
        print("ERROR: nacl_public_key_hex not in config.", file=sys.stderr)
        sys.exit(1)

    phrase = getpass("Backup passphrase: ")
    print("Deriving key (Argon2id)\u2026", end=" ", flush=True)
    try:
        server_sk, server_pk = validate_passphrase(phrase, expected_pub)
    except ValueError as e:
        print(f"\nERROR: {e}", file=sys.stderr)
        sys.exit(1)
    print("\u2713 passphrase verified")

    sender_pk = None
    if kind == "box":
        sender_pk = sender_pk_from_prefix(prefix)
        print(f"Device: {prefix[:16]}… (crypto_box authenticated)")
    else:
        print(f"Legacy backup (crypto_box_seal, unauthenticated)")

    # Load or create restore state
    state_path = output / ".restore_state.json"
    if args.fresh:
        state = RestoreState()
        log.info("Starting fresh (ignoring saved state)")
    else:
        state = RestoreState.load(state_path)
        if state.completed_packs:
            log.info("Resuming: %d packs already completed", len(state.completed_packs))
        if state.inflight:
            log.info("Inflight file: %s (%d/%d bytes)",
                     state.inflight.path, state.inflight.offset, state.inflight.real_size)

    print("Listing pack versions\u2026")
    versions_by_key = b2_list_pack_versions(s3, bucket, prefix)
    all_keys = sorted(versions_by_key.keys())
    skip_count = sum(1 for k in all_keys if k in state.completed_packs)
    remaining = len(all_keys) - skip_count
    total_versions = sum(len(v) for v in versions_by_key.values())
    print(f"Found {len(all_keys)} keys, {total_versions} total versions")
    if skip_count > 0:
        print(f"Skipping {skip_count} already completed, {remaining} remaining")
    if use_cache:
        print(f"Cache: {_cache_dir(bucket)}")
    print(f"\nRestoring into {output}\u2026\n")

    skip_packs = set(state.completed_packs)
    if state.inflight:
        skip_packs.discard(state.inflight.start_pack)

    chunks = iter_plaintext_chunks(
        s3,
        bucket,
        versions_by_key,
        server_sk,
        server_pk,
        prefix,
        sender_pk,
        use_cache,
        args.offline,
        skip_packs=skip_packs,
    )
    file_count, warnings = restore_from_chunks(chunks, output, dump_pax, state, state_path)

    print(f"\n\u2713 {file_count} files restored to {output} (sha256 verified)")
    if warnings:
        print(f"\n\u26a0 {len(warnings)} warnings:")
        for w in warnings:
            print(f"  {w}")


if __name__ == "__main__":
    main()
