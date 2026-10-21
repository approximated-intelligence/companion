package de.perigon.companion.backup.domain

/**
 * Thrown when the eject bus reports the target volume is going away. Not a
 * transport failure: retrying against the same drive cannot succeed, but
 * the *run* can continue (B2, or another drive) and the user needs the
 * actionable message "re-insert and re-run", not "backup storage error".
 */
class DriveEjectedException(message: String) : Exception(message)
