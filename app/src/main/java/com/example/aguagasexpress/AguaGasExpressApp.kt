package com.example.aguagasexpress

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

class AguaGasExpressApp : Application() {
    override fun onCreate() {
        super.onCreate()
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

        // Mantém a sessão anônima usada pelos fluxos de convite/sincronização.
        // O resultado é tratado pelos próprios fluxos da Activity.
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            runCatching {
                val auth = FirebaseAuth.getInstance()
                if (auth.currentUser == null) auth.signInAnonymously()
            }
        }
    }
}
