package de.perigon.companion.backup.domain

import java.nio.charset.StandardCharsets

/**
 * PAX2 archive format writer.
 *
 * Each file entry:
 *   PAX2 header block (512 bytes): magic "PX2\n" + length-prefixed records
 *   File data padded to 512 bytes
 *
 * Records (POSIX PAX extended header convention, always in order):
 *   <len> path=<value>\n
 *   <len> realsize=<value>\n
 *   <len> offset=<value>\n
 *   <len> mtime=<value>\n
 *   <len> sha256=<64 hex chars>\n
 *
 * Where <len> is the decimal length of the entire record including <len> itself,
 * the space, key=value, and the trailing newline.
 *
 * No I/O - all functions return ByteArrays.
 */
object BackupPaxWriter {

    const val BLOCK = 512
    const val MAX_PATH = 256
    private val MAGIC = "PX2\n".toByteArray(StandardCharsets.US_ASCII)

    fun buildEntry(
        path: String,
        realSize: Long,
        offset: Long,
        mtime: Long,
        sha256Hex: String,
    ): ByteArray {
        require(path.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATH) {
            "Path exceeds $MAX_PATH bytes: ${path.length}"
        }
        require(sha256Hex.length == 64) { "sha256 must be 64 hex chars" }

        val records = buildRecords(
            "path" to path,
            "realsize" to realSize.toString(),
            "offset" to offset.toString(),
            "mtime" to mtime.toString(),
            "sha256" to sha256Hex,
        )

        val payloadSize = MAGIC.size + records.size
        require(payloadSize <= BLOCK) {
            "PAX2 header overflow: $payloadSize > $BLOCK"
        }

        val block = ByteArray(BLOCK)
        MAGIC.copyInto(block, 0)
        records.copyInto(block, MAGIC.size)
        return block
    }

    fun parseEntry(block: ByteArray): PaxEntry? {
        if (block.size < BLOCK) return null
        if (block[0] != 'P'.code.toByte() ||
            block[1] != 'X'.code.toByte() ||
            block[2] != '2'.code.toByte() ||
            block[3] != '\n'.code.toByte()
        ) return null

        val text = String(block, MAGIC.size, BLOCK - MAGIC.size, StandardCharsets.UTF_8)
        val fields = parseRecords(text)

        val path = fields["path"] ?: return null
        val realSize = fields["realsize"]?.toLongOrNull() ?: return null
        val offset = fields["offset"]?.toLongOrNull() ?: return null
        val mtime = fields["mtime"]?.toLongOrNull() ?: return null
        val sha256 = fields["sha256"] ?: return null
        if (sha256.length != 64) return null

        return PaxEntry(path, realSize, offset, mtime, sha256)
    }

    fun paddedDataSize(dataSize: Long): Long =
        (dataSize + BLOCK.toLong() - 1L) / BLOCK.toLong() * BLOCK.toLong()

    fun entryOverhead(): Long = BLOCK.toLong()

    private fun buildRecords(vararg entries: Pair<String, String>): ByteArray {
        val parts = entries.map { (key, value) -> buildSingleRecord(key, value) }
        val total = parts.sumOf { it.size }
        val result = ByteArray(total)
        var pos = 0
        parts.forEach { part ->
            part.copyInto(result, pos)
            pos += part.size
        }
        return result
    }

    private fun buildSingleRecord(key: String, value: String): ByteArray {
        val core = " $key=$value\n"
        var len = core.length + 1
        while (true) {
            val candidate = "$len$core"
            if (candidate.length == len) return candidate.toByteArray(StandardCharsets.UTF_8)
            len = candidate.length
        }
    }

    private fun parseRecords(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var pos = 0
        while (pos < text.length) {
            if (text[pos] == '\u0000') break

            val spaceIdx = text.indexOf(' ', pos)
            if (spaceIdx == -1) break
            val len = text.substring(pos, spaceIdx).toIntOrNull() ?: break

            if (pos + len > text.length) break
            val record = text.substring(pos, pos + len)
            if (!record.endsWith("\n")) break

            val payload = record.substring(spaceIdx - pos + 1, record.length - 1)
            val eqIdx = payload.indexOf('=')
            if (eqIdx != -1) {
                result[payload.substring(0, eqIdx)] = payload.substring(eqIdx + 1)
            }

            pos += len
        }
        return result
    }
}

data class PaxEntry(
    val path: String,
    val realSize: Long,
    val offset: Long,
    val mtime: Long,
    val sha256: String,
)
