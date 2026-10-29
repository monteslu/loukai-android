plugins {
    id("com.android.application")
}

// Release signing comes from the environment (CI secrets) so no key or
// password lives in the repo. Without it, release builds use the debug key.
val releaseKeystore = System.getenv("LOUKAI_KEYSTORE_FILE")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "com.loukai.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.loukai.player"
        minSdk = 30
        targetSdk = 36
        versionCode = (System.getenv("LOUKAI_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("LOUKAI_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("LOUKAI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LOUKAI_KEY_ALIAS")
                keyPassword = System.getenv("LOUKAI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
    implementation("androidx.car.app:app:1.7.0")
}
