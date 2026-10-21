package com.example.aguagasexpress

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale

/**
 * Handles data-only FCM messages while the activity is in the background.
 * Voice is spoken locally when Android permits background audio/TTS.
 */
class AppMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.title?.takeIf { it.isNotBlank() }
            ?: "Água & Gás Express"
        val body = data["body"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.body?.takeIf { it.isNotBlank() }
            ?: "Você tem uma atualização do pedido."
        showNotification(title, body)
        if (data["voice"] != "false") startVoiceAnnouncement(body)
    }

    override fun onNewToken(token: String) {
        // The app saves the current token with the customer's order / company config.
        // Token refresh is handled when the relevant profile next opens.
        super.onNewToken(token)
    }

    private fun showNotification(title: String, body: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "aguagas_push"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Avisos de pedidos", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Atualizações de pedidos e entregas"
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
        }
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }

    private fun startVoiceAnnouncement(text: String) {
        try {
            val intent = Intent(this, VoiceAnnouncementService::class.java).putExtra("text", text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            // A notificação escrita continua disponível mesmo se o áudio não puder iniciar.
        }
    }
}
