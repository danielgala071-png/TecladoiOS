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
        versionName = "5.$compilacion"
    }

    buildTypes {
        // GitHub compila la versión "debug": se deja optimizada igual que una versión final
        // (sin modo depuración y con R8), así el teclado va más rápido y gasta menos batería.
        // Se firma igual que antes.
        debug {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
