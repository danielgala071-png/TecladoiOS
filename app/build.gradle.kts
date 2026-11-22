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
        versionName = "7.$compilacion"
    }

    // Firma fija: antes cada compilación de GitHub usaba una llave nueva y Android no dejaba
    // instalar la versión nueva encima (había que desinstalar y se perdía lo aprendido).
    // Con esta llave siempre igual, cada APK nuevo se instala como actualización.
    // La llave ya no vive en el repositorio: GitHub la pone en firma.jks al compilar, desde
    // el secreto FIRMA_BASE64 (y la clave desde FIRMA_CLAVE).
    // Llave nueva desde el 21/11/2026: la anterior quedó pública, así que hubo que cambiarla
    // (una sola vez se desinstala la versión vieja); desde ahí cada APK se instala encima.
    signingConfigs {
        create("fija") {
            // Sin clave por defecto: la llave y su clave solo existen en los secretos de GitHub
            val clave = System.getenv("FIRMA_CLAVE").orEmpty()
            storeFile = file("firma.jks")
            storePassword = clave
            keyAlias = "tecladoios"
            keyPassword = clave
        }
    }

    buildTypes {
        // GitHub compila la versión "debug": se deja optimizada igual que una versión final
        // (sin modo depuración y con R8), así el teclado va más rápido y gasta menos batería.
        // Se firma siempre con la misma llave (firma.jks).
        debug {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("fija")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("fija")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // El diccionario va sin comprimir dentro del APK: así se lee mapeado, sin copiarlo a la RAM
    androidResources {
        noCompress += listOf("txt", "bin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
}
