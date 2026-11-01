plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tecladoios"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tecladoios"
        minSdk = 30
        targetSdk = 35
        // Cada compilación de GitHub tiene su propio número, para saber cuál está instalada
        val compilacion = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = 100 + compilacion
        versionName = "3.$compilacion"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
