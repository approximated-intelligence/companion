package de.perigon.companion.audio.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.AndroidEntryPoint
import de.perigon.companion.MainActivity
import de.perigon.companion.R
import de.perigon.companion.audio.data.AudioConfigEntity
import de.perigon.companion.audio.data.AudioConfigPrefs
import de.perigon.companion.audio.data.AudioFormat
import de.perigon.companion.audio.data.AudioRecordingEntity
import de.perigon.companion.audio.data.AudioRepository
import de.perigon.companion.audio.domain.SilenceGate
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.ui.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class AudioRecordingStatus(
    val isRecording:       Boolean = false,
    val isPaused:          Boolean = false,
    val isAutoPaused:      Boolean = false,
    val currentRecordingId: Long   = 0L,
    val currentName:       String  = "",
    val elapsedMs:         Long    = 0L,
    val amplitudeDb:       Int     = -100,
)

@AndroidEntryPoint
class AudioRecordingService : Service() {

    companion object {
        const val ACTION_START  = "de.perigon.companion.audio.START"
        const val ACTION_STOP   = "de.perigon.companion.audio.STOP"
        const val ACTION_PAUSE  = "de.perigon.companion.audio.PAUSE"
        const val ACTION_RESUME = "de.perigon.companion.audio.RESUME"

        const val NAV_AUDIO = "AudioRecording"

        private const val TAG                  = "AudioRecordingService"
        private const val NOTIFICATION_ID      = 4020
        private const val STOP_REQUEST_CODE    = 9020
        private const val PAUSE_REQUEST_CODE   = 9021
        private const val RESUME_REQUEST_CODE  = 9022
        private const val CONTENT_REQUEST_CODE = 9023

        private const val AMPLITUDE_POLL_MS = 100L

        private val _status = MutableStateFlow(AudioRecordingStatus())
        val status: StateFlow<AudioRecordingStatus> = _status.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Inject lateinit var configPrefs: AudioConfigPrefs
    @Inject lateinit var repository: AudioRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var recorder: MediaRecorder? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null
    private var pfd: ParcelFileDescriptor? = null
    private var fileUri: Uri? = null
    private var recordingId: Long = 0L
    private var recordingName: String = ""
    private var startElapsedMs: Long = 0L
    private var pauseAccumulatedMs: Long = 0L
    private var pauseStartedAtMs: Long = 0L
    private var userPaused: Boolean = false
    private var autoPaused: Boolean = false
    private var amplitudeJob: Job? = null
    private var silenceGate: SilenceGate? = null
    private var config: AudioConfigEntity = AudioConfigEntity.DEFAULT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START  -> startRecording()
            ACTION_STOP   -> stopRecording()
            ACTION_PAUSE  -> userPause()
            ACTION_RESUME -> userResume()
        }
        return START_STICKY
    }

    // ---- Lifecycle ----

    private fun startRecording() {
        if (recorder != null) return
        if (!hasAudioPermission()) { Log.w(TAG, "No RECORD_AUDIO permission"); stopSelf(); return }

        scope.launch {
            val cfg = configPrefs.get()
            val folderUriStr = configPrefs.folderUri()
            if (folderUriStr == null) {
                Log.w(TAG, "No storage folder configured")
                stopSelf()
                return@launch
            }

            val folderUri = Uri.parse(folderUriStr)
            config = cfg

            val name = generateName(cfg.format)
            val created = withContext(Dispatchers.IO) {
                createFile(folderUri, name, cfg.format)
            }
            if (created == null) {
                Log.e(TAG, "Failed to create file in $folderUri")
                stopSelf()
                return@launch
            }

            val (createdUri, parcelFd) = created
            fileUri = createdUri
            pfd = parcelFd
            recordingName = name

            startForeground(NOTIFICATION_ID, buildNotification(cfg), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

            try {
                recorder = buildRecorder(cfg, parcelFd.fileDescriptor).also { it.start() }
            } catch (e: Exception) {
                Log.e(TAG, "MediaRecorder start failed", e)
                cleanupRecorderState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            attachEffects(cfg)

            recordingId = repository.insert(
                AudioRecordingEntity(
                    name         = name,
                    uri          = createdUri.toString(),
                    format       = cfg.format.name,
                    sampleRateHz = cfg.effectiveSampleRateHz,
                    bitrateBps   = cfg.effectiveBitrateBps,
                )
            )

            startElapsedMs     = SystemClock.elapsedRealtime()
            pauseAccumulatedMs = 0L
            userPaused         = false
            autoPaused         = false
            silenceGate = if (cfg.skipSilence && cfg.format.supportsPause)
                SilenceGate(cfg.silenceThresholdDb, cfg.silenceGraceMs) else null

            _status.value = AudioRecordingStatus(
                isRecording       = true,
                currentRecordingId = recordingId,
                currentName       = name,
            )

            startAmplitudePolling()
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: run { stopSelf(); return }
        amplitudeJob?.cancel()
        amplitudeJob = null

        try { rec.stop() } catch (e: Exception) { Log.w(TAG, "MediaRecorder.stop failed: ${e.message}") }
        detachEffects()
        try { rec.release() } catch (_: Exception) {}
        recorder = null

        try { pfd?.close() } catch (_: Exception) {}
        pfd = null

        val durationMs = computeElapsed()
        val capturedUri = fileUri
        val capturedId = recordingId

        fileUri = null
        recordingId = 0L
        recordingName = ""
        silenceGate = null

        appScope.launch(Dispatchers.IO) {
            val sizeBytes = capturedUri?.let { uri ->
                try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                } catch (_: Exception) { null }
            } ?: 0L
            if (capturedId > 0L) {
                repository.finalize(capturedId, durationMs, sizeBytes)
            }
        }

        _status.value = AudioRecordingStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun userPause() {
        val rec = recorder ?: return
        if (userPaused) return
        if (!config.format.supportsPause) return
        try { rec.pause() } catch (e: Exception) { Log.w(TAG, "pause failed", e); return }
        userPaused = true
        pauseStartedAtMs = SystemClock.elapsedRealtime()
        _status.update { it.copy(isPaused = true) }
        refreshNotification()
    }

    private fun userResume() {
        val rec = recorder ?: return
        if (!userPaused) return
        try { rec.resume() } catch (e: Exception) { Log.w(TAG, "resume failed", e); return }
        pauseAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartedAtMs
        userPaused = false
        _status.update { it.copy(isPaused = false) }
        refreshNotification()
    }

    private fun autoPause() {
        val rec = recorder ?: return
        if (autoPaused || userPaused) return
        try { rec.pause() } catch (_: Exception) { return }
        autoPaused = true
        pauseStartedAtMs = SystemClock.elapsedRealtime()
        _status.update { it.copy(isAutoPaused = true) }
    }

    private fun autoResume() {
        val rec = recorder ?: return
        if (!autoPaused) return
        try { rec.resume() } catch (_: Exception) { return }
        pauseAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartedAtMs
        autoPaused = false
        _status.update { it.copy(isAutoPaused = false) }
    }

    // ---- Amplitude polling & silence gate ----

    private fun startAmplitudePolling() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (recorder != null) {
                delay(AMPLITUDE_POLL_MS)
                val rec = recorder ?: break
                val amplitude = try { rec.maxAmplitude } catch (_: Exception) { 0 }
                val db = SilenceGate.amplitudeToDb(amplitude)
                val now = SystemClock.elapsedRealtime()

                silenceGate?.let { gate ->
                    if (!userPaused) {
                        val currentlyCapturing = !autoPaused
                        when (gate.evaluate(amplitude, now, currentlyCapturing)) {
                            SilenceGate.Decision.Pause  -> autoPause()
                            SilenceGate.Decision.Resume -> autoResume()
                            SilenceGate.Decision.Stay   -> {}
                        }
                    }
                }

                _status.update {
                    it.copy(
                        amplitudeDb = db,
                        elapsedMs   = computeElapsed(),
                    )
                }
            }
        }
    }

    private fun computeElapsed(): Long {
        val now = SystemClock.elapsedRealtime()
        val activePause = if (userPaused || autoPaused) now - pauseStartedAtMs else 0L
        return (now - startElapsedMs - pauseAccumulatedMs - activePause).coerceAtLeast(0L)
    }

    // ---- MediaRecorder construction ----

    private fun buildRecorder(cfg: AudioConfigEntity, fd: java.io.FileDescriptor): MediaRecorder {
        val rec = MediaRecorder(this)
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(cfg.format.outputFormat)
        rec.setOutputFile(fd)
        rec.setAudioEncoder(cfg.format.encoder)
        if (!cfg.format.isFixedFormat) {
            rec.setAudioSamplingRate(cfg.effectiveSampleRateHz)
            rec.setAudioEncodingBitRate(cfg.effectiveBitrateBps)
        }
        rec.prepare()
        return rec
    }

    private fun attachEffects(cfg: AudioConfigEntity) {
        // MediaRecorder doesn't expose its AudioSession directly, so effects
        // are attached via session 0 (global). On devices where this fails,
        // we silently skip.
        if (cfg.noiseSuppression && NoiseSuppressor.isAvailable()) {
            try { noiseSuppressor = NoiseSuppressor.create(0)?.apply { enabled = true } }
            catch (_: Exception) {}
        }
        if (cfg.autoGain && AutomaticGainControl.isAvailable()) {
            try { autoGainControl = AutomaticGainControl.create(0)?.apply { enabled = true } }
            catch (_: Exception) {}
        }
    }

    private fun detachEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { autoGainControl?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        autoGainControl = null
    }

    private fun cleanupRecorderState() {
        detachEffects()
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        fileUri = null
    }

    // ---- SAF file creation ----

    private fun createFile(folderUri: Uri, name: String, format: AudioFormat): Pair<Uri, ParcelFileDescriptor>? {
        return try {
            val tree = DocumentFile.fromTreeUri(this, folderUri) ?: return null
            val existing = tree.findFile(name)
            existing?.delete()
            val folderDocId = DocumentsContract.getDocumentId(tree.uri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, folderDocId)
            val created = DocumentsContract.createDocument(contentResolver, parentUri, format.mimeType, name) ?: return null
            val fd = contentResolver.openFileDescriptor(created, "w") ?: return null
            created to fd
        } catch (e: Exception) {
            Log.e(TAG, "createFile failed", e)
            null
        }
    }

    private fun generateName(format: AudioFormat): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "AUD_$ts.${format.extension}"
    }

    // ---- Notification ----

    private fun buildNotification(cfg: AudioConfigEntity): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, NAV_AUDIO)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(this, CONTENT_REQUEST_CODE, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopPi = PendingIntent.getService(this, STOP_REQUEST_CODE,
            Intent(this, AudioRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, NotificationChannels.AUDIO)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Recording audio")
            .setContentText(cfg.format.displayName)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)

        if (cfg.format.supportsPause) {
            val pauseResumeAction = if (userPaused) ACTION_RESUME else ACTION_PAUSE
            val pauseResumeLabel  = if (userPaused) "Resume"       else "Pause"
            val pauseResumeCode   = if (userPaused) RESUME_REQUEST_CODE else PAUSE_REQUEST_CODE
            val pi = PendingIntent.getService(this, pauseResumeCode,
                Intent(this, AudioRecordingService::class.java).apply { action = pauseResumeAction },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, pauseResumeLabel, pi)
        }

        return builder.build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(config))
    }

    override fun onDestroy() {
        amplitudeJob?.cancel()
        recorder?.let { r ->
            try { r.stop() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
        }
        recorder = null
        detachEffects()
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        scope.cancel()
        _status.value = AudioRecordingStatus()
        super.onDestroy()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
