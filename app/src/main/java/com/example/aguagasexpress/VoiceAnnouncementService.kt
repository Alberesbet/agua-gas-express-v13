package com.example.aguagasexpress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Keeps the short text-to-speech announcement alive when an FCM message arrives
 * while the app UI is not visible. The written notification is posted separately.
 */
class VoiceAnnouncementService : Service() {
    private var speech: TextToSpeech? = null
    private var startIdForSpeech: Int = 0

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("aguagas_voice_service", "Avisos falados", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startIdForSpeech = startId
        val text = intent?.getStringExtra("text").orEmpty().take(1000)
        if (text.isBlank()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val notification: Notification = NotificationCompat.Builder(this, "aguagas_voice_service")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Água & Gás Express")
            .setContentText("Reproduzindo aviso de pedido…")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(4802, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(4802, notification)
            }
        } catch (_: Exception) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        try {
            speech?.stop()
            speech?.shutdown()
            speech = TextToSpeech(applicationContext) { status ->
                val engine = speech
                if (engine == null || status != TextToSpeech.SUCCESS) {
                    finishAnnouncement()
                    return@TextToSpeech
                }
                try {
                    val language = engine.setLanguage(Locale("pt", "BR"))
                    if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                        finishAnnouncement()
                        return@TextToSpeech
                    }
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { finishAnnouncement() }
                        @Deprecated("Deprecated in Android API")
                        override fun onError(utteranceId: String?) { finishAnnouncement() }
                        override fun onError(utteranceId: String?, errorCode: Int) { finishAnnouncement() }
                    })
                    @Suppress("DEPRECATION")
                    if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aguagas-voice-$startId") == TextToSpeech.ERROR) {
                        finishAnnouncement()
                    }
                } catch (_: Exception) {
                    finishAnnouncement()
                }
            }
        } catch (_: Exception) {
            finishAnnouncement()
        }
        return START_NOT_STICKY
    }

    private fun finishAnnouncement() {
        try { speech?.stop() } catch (_: Exception) {}
        try { speech?.shutdown() } catch (_: Exception) {}
        speech = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startIdForSpeech)
    }

    override fun onDestroy() {
        try { speech?.stop() } catch (_: Exception) {}
        try { speech?.shutdown() } catch (_: Exception) {}
        speech = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
