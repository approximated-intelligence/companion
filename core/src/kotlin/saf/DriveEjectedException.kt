package de.perigon.companion.core.saf

/**
 * Thrown when the eject bus reports the target volume is going away.
 *
 * Lives in core/saf next to [ExternalDriveEjectBus], the state it is the
 * exception form of. Not a transport failure: retrying against the same
 * drive cannot succeed, but the *run* can continue (B2, or another drive)
 * and the user needs the actionable message "re-insert and re-run", not
 * "backup storage error".
 *
 * Previously this was `backup.domain.DriveEjectedException`, which forced
 * `MirrorEngine` and `MirrorWorker` to reach into a writer-side package for
 * a signal that is really about the platform's eject handshake — the same
 * shape as [ExternalDriveEjectBus], which has always been in core.
 */
class DriveEjectedException(message: String) : Exception(message)
