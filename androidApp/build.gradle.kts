plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Tag-driven releases: -PversionName=1.2.3 -PversionCode=1002003
// or env VERSION_NAME / VERSION_CODE. Local defaults mirror v1.0.4 release metadata.
fun appVersionProp(name: String, envKey: String, default: String): String =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: default

val appVersionName: String = appVersionProp("versionName", "VERSION_NAME", "1.0.6")
val appVersionCode: Int = appVersionProp("versionCode", "VERSION_CODE", "1000006").toInt()

android {
    namespace = "com.bettermifitness.sync"
    // API 37 is a preview platform published as android-37.0; minorApiLevel
    // points AGP at platforms/android-37.0 instead of android-37.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.bettermifitness.sync"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Release signing: NEVER commit a keystore. Provide your own via ONE of:
    //   1. keystore.properties in androidApp/ (gitignored):
    //        storeFile=/path/to/release.keystore
    //        storePassword=...
    //        keyAlias=...
    //        keyPassword=...
    //   2. Env vars: KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
    // If none is provided, the release build stays unsigned (CI must inject secrets).
    val keystorePropsFile = rootProject.file("androidApp/keystore.properties")
    val keystoreProps = java.util.Properties()
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    }
    fun prop(name: String, env: String): String? =
        keystoreProps.getProperty(name) ?: System.getenv(env)

    signingConfigs {
        create("release") {
            val storePath = prop("storeFile", "KEYSTORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = prop("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = prop("keyAlias", "KEY_ALIAS")
                keyPassword = prop("keyPassword", "KEY_PASSWORD")
            }
            // If storePath is blank the config stays empty and Gradle will
            // fail with a clear error instead of silently signing with a debug key.
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Keep all moko locales in the APK/AAB (system language can switch without redownload).
    bundle {
        language {
            enableSplit = false
        }
    }
}

// AGP 9 built-in Kotlin: configure JVM target without kotlin-android plugin.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.health.connect)
}
