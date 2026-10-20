package de.perigon.companion.sensors.share

import androidx.core.content.FileProvider

/**
 * Track's own provider class: two modules declaring androidx FileProvider
 * itself would collide in the merged manifest. Serves cache/gpx_share only.
 */
class SensorsFileProvider : FileProvider()
