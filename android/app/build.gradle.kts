// Grabadora para Android: la versión NATIVA de recorder.dotrino.com (CONVENCIONES §16).
// Excepción declarada por el dueño (2026-10-29): Android va primero porque la necesita ya; la
// PWA y la de iOS están pendientes. Cuando exista la PWA, `versionName` es la versión de la PWA
// con la que esta está a la par (§16.3).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.dotrino.recorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dotrino.recorder"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.dotrino:dotrino-native")   // includeBuild de ../native/android
    testImplementation("junit:junit:4.13.2")
}
