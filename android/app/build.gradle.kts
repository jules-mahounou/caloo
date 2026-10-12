plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Clé de signature fournie par la CI (secrets GitHub). Sans elle : clé de debug.
val keystore = System.getenv("CALOO_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.caloo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.caloo"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("CALOO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CALOO_KEY_ALIAS") ?: "caloo"
                keyPassword = System.getenv("CALOO_KEY_PASSWORD") ?: System.getenv("CALOO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}
