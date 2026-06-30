package com.example.soundtovibro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Pure-native foreground service.
 *
 * Captures raw PCM straight from [AudioRecord] on a dedicated thread (never
 * touches disk), converts each ~20ms chunk into an RMS/dB reading, and turns
 * that into a vibration via the platform [Vibrator] API directly — no Dart
 * background isolate is involved, which is what made the previous
 * `flutter_foreground_task` + `record` approach unreliable.
 */
class SoundToVibroService : Service() {

    companion object {
        const val CHANNEL_ID = "sound_to_vibro_channel"
        const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.example.soundtovibro.STOP"

        // --- Capture tuning ---
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_DURATION_MS = 20L // -> 50 readings/sec, well above the 20/sec requirement

        // --- Loudness mapping ---
        private const val MIN_DB = -40.0
        private const val MAX_DB = -20.0
        private const val VIBRATION_THRESHOLD = 0.1 // normalized 0..1, below this = silence = no buzz

        // --- Vibration throttling (avoids spamming the vibrator HAL) ---
        private const val MIN_VIBRATION_GAP_MS = 50L

        /** Set by MainActivity's EventChannel while Flutter UI is listening. Safe to be null. */
        @Volatile
        var dataListener: ((Map<String, Any>) -> Unit)? = null

        @Volatile
        var isRunning = false
            private set
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var keepRunning = false

    private lateinit var vibrator: Vibrator
    private var hasVibrator = false
    private var hasAmplitudeControl = false
    private var lastVibrationTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        hasVibrator = vibrator.hasVibrator()
        hasAmplitudeControl = vibrator.hasAmplitudeControl()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCaptureAndSelf()
            return START_NOT_STICKY
        }

        // Must call startForeground() within a few ms of service creation on
        // Android 8+ or the system kills the process.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!isRunning) {
            startCapture()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, SoundToVibroService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sound to Vibro is active")
            .setContentText("Listening to ambient sound")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sound to Vibro Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while the app is listening to ambient sound"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            emitError("Unable to determine AudioRecord buffer size on this device")
            stopCaptureAndSelf()
            return
        }

        val chunkSamples = ((SAMPLE_RATE * CHUNK_DURATION_MS) / 1000L).toInt()
        val bufferSizeBytes = maxOf(minBufferSize, chunkSamples * 2 * 4)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes
            )
        } catch (e: SecurityException) {
            emitError("Microphone permission missing: ${e.message}")
            stopCaptureAndSelf()
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            emitError("AudioRecord failed to initialize")
            record.release()
            stopCaptureAndSelf()
            return
        }

        audioRecord = record
        keepRunning = true
        isRunning = true

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val shortBuffer = ShortArray(chunkSamples)

            try {
                record.startRecording()

                while (keepRunning) {
                    val read = record.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val rms = computeRms(shortBuffer, read)
                        val db = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else MIN_DB
                        val normalized = normalize(db)

                        handleVibration(normalized)

                        val intensityPercent = (normalized * 100).toInt()
                        mainHandler.post {
                            dataListener?.invoke(
                                mapOf(
                                    "db" to db,
                                    "intensity" to intensityPercent,
                                    "hasVibrator" to hasVibrator,
                                    "hasAmplitudeControl" to hasAmplitudeControl
                                )
                            )
                        }
                    }
                }
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                record.release()
            }
        }, "AudioCaptureThread")
        captureThread?.start()
    }

    private fun computeRms(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length)
    }

    private fun normalize(db: Double): Double {
        val clamped = db.coerceIn(MIN_DB, MAX_DB)
        return (clamped - MIN_DB) / (MAX_DB - MIN_DB)
    }

    /**
     * Re-triggers a short vibration whenever loud enough, throttled so calls
     * never fire faster than [MIN_VIBRATION_GAP_MS] apart. Each new call to
     * vibrate() implicitly cancels/replaces the previous one, and because the
     * pulse duration is kept slightly longer than the gap between triggers,
     * the pulses overlap just enough to read as one continuous buzz rather
     * than a series of taps.
     */
    private fun handleVibration(normalized: Double) {
        if (!hasVibrator || normalized < VIBRATION_THRESHOLD) return

        val now = System.currentTimeMillis()
        if (now - lastVibrationTime < MIN_VIBRATION_GAP_MS) return
        lastVibrationTime = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (hasAmplitudeControl) {
                val amplitude = (normalized * 255 * 1.5).toInt().coerceIn(5, 255)
                val duration = (100 + normalized * 100).toLong() // ~70-130ms
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                // No amplitude control: vary pulse duration/frequency instead,
                // at default strength.
                val duration = (100 + normalized * 100).toLong()
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate((100 + normalized * 100).toLong())
        }
    }

    private fun emitError(message: String) {
        mainHandler.post { dataListener?.invoke(mapOf("error" to message)) }
    }

    private fun stopCaptureAndSelf() {
        keepRunning = false
        isRunning = false
        captureThread?.join(500)
        captureThread = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        keepRunning = false
        isRunning = false
        captureThread?.join(500)
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        audioRecord = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
