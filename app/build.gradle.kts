plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.aguagasexpress"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.aguagasexpress"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "13.1"
    }
    flavorDimensions += "perfil"
    productFlavors {
        create("desenvolvedor") {
            dimension = "perfil"
            // Keep developer/admin data isolated from the company/client app.
            applicationIdSuffix = ".desenvolvedor"
            versionNameSuffix = "-desenvolvedor"
            buildConfigField("String", "DEFAULT_PROFILE", "\"proprietario\"")
        }
        create("cliente") {
            dimension = "perfil"
            applicationIdSuffix = ".cliente"
            versionNameSuffix = "-cliente"
            buildConfigField("String", "DEFAULT_PROFILE", "\"cliente\"")
        }
        create("entregador") {
            dimension = "perfil"
            applicationIdSuffix = ".entregador"
            versionNameSuffix = "-entregador"
            buildConfigField("String", "DEFAULT_PROFILE", "\"entregador\"")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
}
