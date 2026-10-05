package com.example.aguagasexpress

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

fun startDeliveryLocationService(context: Context, orderId: String) {
    val intent = Intent(context, DeliveryLocationService::class.java).apply {
        putExtra("orderId", orderId)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}

class DeliveryLocationService : Service(), LocationListener {
    private var orderId: String = ""
    private lateinit var locationManager: LocationManager
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(4801, notification())
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        orderId = intent?.getStringExtra("orderId").orEmpty()
        if (orderId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: Exception) { }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (orderId.isBlank()) return
        val point = mapOf("lat" to location.latitude, "lng" to location.longitude)
        val ref = firestore.collection("orders").document(orderId)
        firestore.runTransaction { tx ->
            val snap = tx.get(ref)
            val old = (snap.get("trackingPath") as? List<*>)?.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val lat = (m["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
                val lng = (m["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
                mapOf("lat" to lat, "lng" to lng)
            } ?: emptyList()
            val newPath = (old + point).takeLast(300)
            tx.set(ref, mapOf(
                "trackingLat" to location.latitude,
                "trackingLng" to location.longitude,
                "trackingActive" to true,
                "trackingPath" to newPath
            ), SetOptions.merge())
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Android API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        if (orderId.isNotBlank()) {
            firestore.collection("orders").document(orderId).set(mapOf("trackingActive" to false), SetOptions.merge())
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("entrega_rastreamento", "Rastreamento da entrega", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, "entrega_rastreamento")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Entrega em andamento")
        .setContentText("Compartilhando a localização do entregador.")
        .setOngoing(true)
        .build()
}
