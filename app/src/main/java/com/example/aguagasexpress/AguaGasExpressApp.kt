package com.example.aguagasexpress

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

class AguaGasExpressApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase must not prevent the launcher Activity from opening if startup
        // configuration or an offline auth request fails. Individual screens show
        // the Firebase error and can retry their own operations.
        runCatching {
            val options = FirebaseOptions.Builder()
                .setApplicationId("1:1071136912572:android:477d843eb2dad93ef38cb9")
                .setApiKey("AIzaSyBYqd8kTRq_cV5suBSWK-qTjldaOJm7h-8")
                .setProjectId("agua-e-gas-express")
                .setGcmSenderId("1071136912572")
                .setStorageBucket("agua-e-gas-express.firebasestorage.app")
                .build()

            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this, options)
            }

            if (FirebaseApp.getApps(this).isNotEmpty()) {
                runCatching {
                    val auth = FirebaseAuth.getInstance()
                    if (auth.currentUser == null) auth.signInAnonymously()
                }.onFailure { Log.e("AguaGasExpress", "Falha ao iniciar autenticação Firebase", it) }
            }
        }.onFailure { Log.e("AguaGasExpress", "Falha na inicialização do Firebase; o app continuará abrindo", it) }
    }
}
