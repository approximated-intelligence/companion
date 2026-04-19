package de.perigon.companion.track.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import de.perigon.companion.MainActivity
import de.perigon.companion.R
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.track.data.RecordingMode
import de.perigon.companion.track.data.TrackConfigEntity
import de.perigon.companion.track.data.TrackConfigPrefs
import de.perigon.companion.track.data.TrackPointBuffer
import de.perigon.companion.track.data.TrackPointEntity
import de.perigon.companion.track.data.TrackRepository
import de.perigon.companion.track.domain.FixEffect
import de.perigon.companion.track.domain.FixEvent
import de.perigon.companion.track.domain.FixState
import de.perigon.companion.track.domain.FixStrategy
import de.perigon.companion.track.domain.Background
import de.perigon.companion.track.domain.BackgroundConfig
import de.perigon.companion.track.domain.fixTransition
import de.perigon.companion.track.domain.formatIntervalLabel
import de.perigon.companion.track.domain.formatModeLabel
import de.perigon.companion.core.prefs.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.FloatBuffer
import java.time.LocalDate
import javax.inject.Inject

data class RecordingStatus(
    val isRecording:     Boolean           = false,
    val lastProvidedFix: TrackPointEntity? = null,
    val lastAcceptedFix: TrackPointEntity? = null,
    val pendingPoints:   Int               = 0,
    val gnssInfo:        GnssInfo          = GnssInfo.EMPTY,
)

@AndroidEntryPoint
class BackgroundService : Service() {

    companion object {
        const val ACTION_START       = "de.perigon.companion.track.START"
        const val ACTION_STOP        = "de.perigon.companion.track.STOP"
        const val ACTION_PAUSE       = "de.perigon.companion.track.PAUSE"
        const val ACTION_NEW_SEGMENT = "de.perigon.companion.track.NEW_SEGMENT"
        const val ACTION_FIX_DUE     = "de.perigon.companion.track.FIX_DUE"
        const val ACTION_FLUSH       = "de.perigon.companion.track.FLUSH"
        const val EXTRA_NAVIGATE_TO  = "navigate_to"
        const val NAV_RECORDING      = "BackgroundService"

        private const val TAG                  = "BackgroundService"
        private const val CHANNEL_ID           = "track_recorder"
        private const val NOTIFICATION_ID      = 4001
        private const val STOP_REQUEST_CODE    = 9002
        private const val CONTENT_REQUEST_CODE = 9003

        private val _status = MutableStateFlow(RecordingStatus())
        val status: StateFlow<RecordingStatus> = _status.asStateFlow()

        val isRecording     get() = _status.map { it.isRecording }
        val lastProvidedFix get() = _status.map { it.lastProvidedFix }
        val lastAcceptedFix get() = _status.map { it.lastAcceptedFix }
        val pendingPoints   get() = _status.map { it.pendingPoints }
        val gnssInfo        get() = _status.map { it.gnssInfo }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Inject lateinit var appPrefs: AppPrefs
    @Inject lateinit var repository: TrackRepository
    @Inject lateinit var configPrefs: TrackConfigPrefs
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private lateinit var fixScheduler: FixScheduler
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var geoid: FloatBuffer

    private var buffer: TrackPointBuffer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var config   = BackgroundConfig(RecordingMode.CONTINUOUS, 10_000L, 30_000L, 50f, true, true, true, TrackConfigEntity.DEFAULT_AUTO_SEGMENT_GAP_MS)
    private var strategy: FixStrategy = FixStrategy.Continuous(10_000L)
    private var trackId     = 0L
    private var segmentId   = 0L
    private var currentDate = ""
    private var recording   = false
    private var configJob:  Job? = null
    private var ephemerisWarmupActive  = false
    private var gnssCallbackRegistered = false
    private var sessionWakeLockHeld    = false
    private var fixTimeoutJob: Job? = null
    private var fixState: FixState = FixState.Idle
    private var recordingStartElapsedNanos: Long = 0L

    private val isSingleFixMode: Boolean
        get() = config.mode == RecordingMode.SINGLE_FIX || config.mode == RecordingMode.ALARM

    // ---- Geoid ----

    private fun linearInterpolate(a: Float, b: Float, t: Float) = a + t * (b - a)

    private fun undulation(lat: Double, lon: Double): Float {
        val cols = 1441
        val step = 0.25
        val normLon = when {
            lon < -180.0 -> lon + 360.0
            lon > 180.0  -> lon - 360.0
            else         -> lon
        }
        val x  = (normLon + 180.0) / step
        val y  = (lat + 90.0) / step
        val x0 = x.toInt().coerceIn(0, cols - 2)
        val y0 = y.toInt().coerceIn(0, 719)
        val x1 = x0 + 1
        val y1 = y0 + 1
        val tx = (x - x0).toFloat()
        val ty = (y - y0).toFloat()
        return linearInterpolate(
            linearInterpolate(geoid.get(y0 * cols + x0), geoid.get(y0 * cols + x1), tx),
            linearInterpolate(geoid.get(y1 * cols + x0), geoid.get(y1 * cols + x1), tx),
            ty,
        )
    }

    private fun convert(location: Location): TrackPointEntity {
        val undulation = undulation(location.latitude, location.longitude)
        return TrackPointEntity(
            segmentId  = 0L,
            lat        = location.latitude.toFloat(),
            lon        = location.longitude.toFloat(),
            ele        = if (location.hasAltitude()) (location.altitude - undulation).toFloat() else null,
            undulation = undulation,
            accuracyM  = if (location.hasAccuracy()) location.accuracy else null,
            speedMs    = if (location.hasSpeed()) location.speed else null,
            bearing    = if (location.hasBearing()) location.bearing else null,
            time       = location.time,
            provider   = location.provider ?: "gps",
        )
    }

    // ---- GNSS callbacks ----

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val counts = mutableMapOf<Int, MutableList<Boolean>>()
            for (i in 0 until status.satelliteCount) {
                val type = status.getConstellationType(i)
                counts.getOrPut(type) { mutableListOf() }.add(status.usedInFix(i))
            }
            _status.update {
                it.copy(gnssInfo = GnssInfo(constellations = counts.mapValues { (_, sats) ->
                    ConstellationCount(used = sats.count { it }, visible = sats.size)
                }))
            }
        }
    }

    private var lastFixRealtimeNanos: Long = -1L

    private fun emitLocation(location: Location) {
        val ts = location.elapsedRealtimeNanos
        if (ts == lastFixRealtimeNanos) return
        if (ts < recordingStartElapsedNanos) return
        lastFixRealtimeNanos = ts
        dispatchFixEvent(FixEvent.FixProvided(convert(location)))
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { emitLocation(location) }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
    }

    private val ephemerisListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { emitLocation(location) }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager     = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        fixScheduler        = FixScheduler(this) { dispatchFixEvent(FixEvent.StartCycle) }

        // Copy the geoid.bin to cache directory
        val geoidFile = File(cacheDir, "geoid.bin")
        if (!geoidFile.exists()) {
            assets.open("gnss/geoid.bin").use { inputStream ->
                FileOutputStream(geoidFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }

        // Map the file from the cache directory
        geoid = FileInputStream(geoidFile).channel
            .map(FileChannel.MapMode.READ_ONLY, 0, geoidFile.length())
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // geoid               = assets.openFd("gnss/geoid.bin").use { afd ->
        //    FileInputStream(afd.fileDescriptor).channel
        //        .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
        //        .order(ByteOrder.nativeOrder())
        //        .asFloatBuffer()
        // }

        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START       -> startRecording()
            ACTION_STOP        -> stopRecording()
            ACTION_PAUSE       -> pauseRecording()
            ACTION_NEW_SEGMENT -> newSegment()
            ACTION_FLUSH       -> flushBuffer()
            ACTION_FIX_DUE     -> dispatchFixEvent(FixEvent.StartCycle)
        }
        return START_STICKY
    }

    // ---- Core state machine ----

    private fun dispatchFixEvent(event: FixEvent) {
        if (!recording && event !is FixEvent.Cancel) return
        val transition = fixTransition(
            state           = fixState,
            event           = event,
            maxInaccuracyM  = config.maxInaccuracyM,
            isSingleFixMode = isSingleFixMode,
        )
        fixState = transition.state
        executeEffects(transition.effects)
    }

    private fun executeEffects(effects: List<FixEffect>) {
        for (effect in effects) {
            when (effect) {
                is FixEffect.ProvidedFix        -> _status.update { it.copy(lastProvidedFix = effect.point) }
                is FixEffect.AcceptFix          -> { _status.update { it.copy(lastAcceptedFix = effect.point) }; bufferPoint(effect.point) }
                is FixEffect.RequestGpsFix      -> requestSingleGpsFix()
                is FixEffect.ScheduleNext       -> fixScheduler.schedule(strategy)
                is FixEffect.AcquireFixWakeLock -> acquireFixWakeLock()
                is FixEffect.ReleaseFixWakeLock -> releaseFixWakeLock()
                is FixEffect.SkipCycle          -> Log.d(TAG, "Skipping fix cycle: ${effect.reason}")
            }
        }
    }

    // ---- Recording lifecycle ----

    private fun startRecording() {
        if (recording) return
        if (!appPrefs.isBackgroundGpsEnabled()) { Log.w(TAG, "Recording disabled"); stopSelf(); return }
        if (!hasLocationPermission()) { Log.w(TAG, "No location permission"); stopSelf(); return }

        recording = true
        fixState  = FixState.Idle
        recordingStartElapsedNanos =
            SystemClock.elapsedRealtimeNanos() - config.fixTimeoutMs * 1_000_000L
        _status.value = RecordingStatus(isRecording = true)

        scope.launch {
            val cfg  = configPrefs.get()
            config   = cfg.toRecorderConfig()
            strategy = Background.strategyFor(config.mode, config.intervalMs)

            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

            val (tId, sId) = repository.startRecording(config.autoSplitOnDayRollover)
            trackId     = tId
            segmentId   = sId
            currentDate = LocalDate.now().toString()
            buffer = TrackPointBuffer(repository, config.intervalMs, config.mode).also {
                it.setSegmentId(segmentId)
            }
            registerGnssCallback()
            applyWakeLock()
            applyEphemeris()
            beginFixCycle()
            observeConfigChanges()
        }
    }

    private fun pauseRecording() {
        if (!recording) return
        recording = false
        teardownGps()
        val capturedBuffer = buffer
        buffer = null
        appScope.launch {
            capturedBuffer?.flushRemaining()
            repository.pauseRecording(trackId)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        recording = false
        teardownGps()
        val capturedBuffer = buffer
        buffer = null
        appScope.launch {
            capturedBuffer?.flushRemaining()
            repository.stopRecording()
            _status.value = RecordingStatus()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun newSegment() {
        if (trackId == 0L) return
        scope.launch {
            buffer?.flush()
            val newSegId = repository.startNewSegment(trackId)
            segmentId = newSegId
            buffer?.setSegmentId(newSegId)
            _status.update { it.copy(pendingPoints = buffer?.pendingCount() ?: 0) }
        }
    }

    private fun teardownGps() {
        dispatchFixEvent(FixEvent.Cancel)
        fixTimeoutJob?.cancel(); fixTimeoutJob = null
        configJob?.cancel(); configJob = null
        stopPrimaryListener()
        stopEphemerisWarmup()
        unregisterGnssCallback()
        fixScheduler.cancel()
        releaseSessionWakeLock()
        releaseFixWakeLock()
    }

    private fun flushBuffer() {
        scope.launch {
            buffer?.flushRemaining()
            _status.update { it.copy(pendingPoints = buffer?.pendingCount() ?: 0) }
        }
    }

    // ---- Config observation ----

    private fun observeConfigChanges() {
        configJob?.cancel()
        configJob = scope.launch {
            configPrefs.observe()
                .map { it.toRecorderConfig() }
                .distinctUntilChanged()
                .collect { newConfig ->
                    if (newConfig != config) applyConfigChange(newConfig)
                }
        }
    }

    private fun applyConfigChange(newConfig: BackgroundConfig) {
        dispatchFixEvent(FixEvent.Cancel)
        fixTimeoutJob?.cancel(); fixTimeoutJob = null
        config   = newConfig
        strategy = Background.strategyFor(config.mode, config.intervalMs)
        buffer?.updateConfig(config.mode, config.intervalMs)
        stopPrimaryListener()
        fixScheduler.cancel()
        applyWakeLock()
        applyEphemeris()
        beginFixCycle()
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ---- Fix cycle ----

    private fun beginFixCycle() {
        checkDayRollover()
        when (strategy) {
            is FixStrategy.Continuous    -> startContinuousUpdates()
            is FixStrategy.DelayedSingle -> dispatchFixEvent(FixEvent.StartCycle)
            is FixStrategy.AlarmSingle   -> dispatchFixEvent(FixEvent.StartCycle)
            is FixStrategy.Passive       -> { if (!ephemerisWarmupActive) startPassiveUpdates() }
        }
    }

    private fun checkDayRollover() {
        val today = LocalDate.now().toString()
        if (today == currentDate) return
        currentDate = today
        scope.launch {
            buffer?.flushRemaining()
            _status.update { it.copy(pendingPoints = 0) }
            val (tId, sId) = repository.handleDayRollover(config.autoSplitOnDayRollover)
            trackId   = tId
            segmentId = sId
            buffer?.setSegmentId(segmentId)
        }
    }

    private fun startContinuousUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                (strategy as FixStrategy.Continuous).minTimeMs,
                0f,
                locationListener,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission lost", e)
            stopRecording()
        }
    }

    private fun startPassiveUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER, 0L, 0f, locationListener)
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission lost", e)
            stopRecording()
        }
    }

    // ---- Single GPS fix ----

    private fun requestSingleGpsFix() {
        if (!hasLocationPermission()) { dispatchFixEvent(FixEvent.Timeout); return }
        fixTimeoutJob?.cancel()
        fixTimeoutJob = scope.launch {
            delay(config.fixTimeoutMs)
            dispatchFixEvent(FixEvent.Timeout)
        }
        try {
            locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER, null, mainExecutor) { location ->
                fixTimeoutJob?.cancel()
                if (location != null) emitLocation(location) else dispatchFixEvent(FixEvent.Timeout)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission lost", e)
            fixTimeoutJob?.cancel()
            releaseFixWakeLock()
        }
    }

    private fun stopPrimaryListener() {
        locationManager.removeUpdates(locationListener)
    }

    // ---- Point buffering ----

    private fun bufferPoint(point: TrackPointEntity) {
        val buf = buffer ?: return

        val lastFix = _status.value.lastAcceptedFix
        if (lastFix != null && config.autoSegmentGapMs > 0 &&
            point.time - lastFix.time > config.autoSegmentGapMs) {
            scope.launch {
                buf.flush()
                val newSegId = repository.startNewSegment(trackId)
                segmentId = newSegId
                buf.setSegmentId(newSegId)
                buf.add(point)
                _status.update { it.copy(pendingPoints = buf.pendingCount()) }
            }
            return
        }

        buf.add(point)
        _status.update { it.copy(pendingPoints = buf.pendingCount()) }
        if (buf.shouldFlush()) {
            scope.launch {
                buf.flush()
                _status.update { it.copy(pendingPoints = buf.pendingCount()) }
            }
        }
    }

    // ---- GNSS status ----

    private fun registerGnssCallback() {
        if (gnssCallbackRegistered) return
        if (!hasLocationPermission()) return
        try {
            locationManager.registerGnssStatusCallback(mainExecutor, gnssStatusCallback)
            gnssCallbackRegistered = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot register GNSS callback", e)
        }
    }

    private fun unregisterGnssCallback() {
        if (!gnssCallbackRegistered) return
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        gnssCallbackRegistered = false
        _status.update { it.copy(gnssInfo = GnssInfo.EMPTY) }
    }

    // ---- Wake lock ----

    private fun ensureWakeLock(): PowerManager.WakeLock {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerigonCompanion:TrackFix")
        }
        return wakeLock!!
    }

    private fun applyWakeLock() {
        if (config.holdWakeLock) acquireSessionWakeLock() else releaseSessionWakeLock()
    }

    private fun acquireSessionWakeLock() {
        if (sessionWakeLockHeld) return
        ensureWakeLock().acquire()
        sessionWakeLockHeld = true
    }

    private fun releaseSessionWakeLock() {
        if (!sessionWakeLockHeld) return
        wakeLock?.let { if (it.isHeld) it.release() }
        sessionWakeLockHeld = false
    }

    private fun acquireFixWakeLock() {
        if (sessionWakeLockHeld) return
        ensureWakeLock().acquire(config.fixTimeoutMs * 3)
    }

    private fun releaseFixWakeLock() {
        if (sessionWakeLockHeld) return
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ---- Ephemeris ----

    private fun applyEphemeris() {
        if (config.keepEphemerisWarm) startEphemerisWarmup() else stopEphemerisWarmup()
    }

    private fun startEphemerisWarmup() {
        if (ephemerisWarmupActive) return
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, ephemerisListener)
            ephemerisWarmupActive = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Ephemeris warmup failed", e)
        }
    }

    private fun stopEphemerisWarmup() {
        if (!ephemerisWarmupActive) return
        locationManager.removeUpdates(ephemerisListener)
        ephemerisWarmupActive = false
    }

    // ---- Notification ----

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background Track", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val modeLabel = formatModeLabel(config.mode)
        val text = if (config.mode == RecordingMode.PASSIVE) modeLabel
                   else "${formatIntervalLabel(config.intervalMs)} · $modeLabel"
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_NAVIGATE_TO, NAV_RECORDING)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(this, CONTENT_REQUEST_CODE, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, STOP_REQUEST_CODE,
            Intent(this, BackgroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Run")
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    // ---- Lifecycle ----

    override fun onTaskRemoved(rootIntent: Intent?) {
        appScope.launch { buffer?.flushRemaining() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        recording = false
        teardownGps()
        val capturedBuffer = buffer
        buffer = null
        appScope.launch { withTimeoutOrNull(2000) { capturedBuffer?.flushRemaining() } }
        scope.launch { delay(2100); scope.cancel() }
        _status.value = RecordingStatus()
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}

private fun TrackConfigEntity.toRecorderConfig() = BackgroundConfig(
    mode                   = mode,
    intervalMs             = intervalMs,
    fixTimeoutMs           = fixTimeoutMs,
    maxInaccuracyM         = maxInaccuracyM,
    keepEphemerisWarm      = keepEphemerisWarm,
    holdWakeLock           = holdWakeLock,
    autoSplitOnDayRollover = autoSplitOnDayRollover,
    autoSegmentGapMs       = autoSegmentGapMs,
)
