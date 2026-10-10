plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

// Atalho na raiz: gera os APKs dos três perfis sem depender do Build Variant selecionado no Android Studio.
tasks.register("assembleAllProfilesDebug") {
    group = "build"
    description = "Compila os APKs Desenvolvedor, Cliente e Entregador."
    dependsOn(
        ":app:assembleDesenvolvedorDebug",
        ":app:assembleClienteDebug",
        ":app:assembleEntregadorDebug"
    )
}
