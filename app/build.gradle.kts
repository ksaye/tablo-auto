import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing details are kept out of the repository. build-apk.sh points at a keystore.env holding
// the four values below; without it the build still works and produces a debug-signed APK, which
// is fine for trying the app but cannot upgrade an install signed with the real key.
val keystoreEnv = Properties().apply {
    val file = rootProject.file("keystore.env")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(name: String): String? =
    (keystoreEnv.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

// Where the app looks for new versions of itself. The defaults are this project's public GitHub
// releases, which is right for anyone who clones the repository. A private build points somewhere
// else -- a Gitea server on the house network, say -- through an update.env that is not committed,
// so no token and no private address ever lands in the repository.
val updateEnv = Properties().apply {
    val file = rootProject.file("update.env")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun updateSetting(name: String, fallback: String): String =
    (updateEnv.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "io.github.ksaye.tabloauto"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ksaye.tabloauto"
        // Android Auto itself needs 23; 26 is what the media notification and the foreground
        // service plumbing assume, and no car head unit predates it.
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        buildConfigField("String", "UPDATE_API_BASE",
            "\"${updateSetting("UPDATE_API_BASE", "https://api.github.com")}\"")
        buildConfigField("String", "UPDATE_OWNER",
            "\"${updateSetting("UPDATE_OWNER", "ksaye")}\"")
        buildConfigField("String", "UPDATE_REPO",
            "\"${updateSetting("UPDATE_REPO", "tablo-auto")}\"")
        buildConfigField("String", "UPDATE_TOKEN",
            "\"${updateSetting("UPDATE_TOKEN", "")}\"")
    }

    signingConfigs {
        create("release") {
            val path = secret("KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD") ?: secret("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (secret("KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-session:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // ListenableFuture from a coroutine: the media session callbacks are all future-returning.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
}
