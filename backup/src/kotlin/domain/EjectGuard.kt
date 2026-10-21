package de.perigon.companion.backup.domain

import de.perigon.companion.core.saf.ExternalDriveEjectBus

/**
 * Consulted before each write to a destination the platform might be tearing
 * down. `BackupOrchestrator` calls it before every part upload; the
 * alternative is letting the write fail with EBUSY (or, worse, succeed into
 * a filesystem that is being unmounted) and reporting it as a transport
 * error — the wrong category, and it leaves a half-written pack on a drive
 * that is about to disappear.
 *
 * The sealed shape is deliberate. A nullable
 * `ejectBus: ExternalDriveEjectBus? = null` parameter would let a caller
 * forget the check by omitting the argument: the code would compile, the run
 * would proceed, and a mid-run eject would surface later as a generic
 * `IOException`. `EjectGuard` makes the missing check impossible to write by
 * omission — every `BackupOrchestrator` is constructed with an explicit
 * guard, and `EjectGuard.None` reads as "this destination has nothing to
 * eject" (B2) rather than as a forgotten argument.
 */
sealed interface EjectGuard {
    fun check()

    /** B2. Nothing to check. */
    data object None : EjectGuard {
        override fun check() = Unit
    }

    /**
     * A thumb drive. [uuid] is the volume UUID used by [ExternalDriveEjectBus]
     * (the "XXXX-XXXX:" prefix of the SAF tree document id). Required, not
     * nullable: a drive destination whose tree URI does not carry a UUID is
     * a configuration error, not a silent no-op.
     */
    class ForDrive(
        private val bus: ExternalDriveEjectBus,
        private val uuid: String,
    ) : EjectGuard {
        override fun check() {
            if (bus.isEjecting(uuid)) {
                throw DriveEjectedException("drive $uuid was ejected")
            }
        }
    }
}
