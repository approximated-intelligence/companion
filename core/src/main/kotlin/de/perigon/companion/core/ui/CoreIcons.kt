package de.perigon.companion.core.ui

import de.perigon.companion.core.R

/**
 * Core-owned drawables other modules reference. With non-transitive R
 * classes a feature cannot reach core's R directly; this is the one handle.
 */
object CoreIcons {
    /** Monochrome status-bar icon for every notification. */
    val status: Int = R.drawable.ic_stat_companion
}
