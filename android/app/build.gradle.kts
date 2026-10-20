import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The phone app and the band rpk MUST share one signing certificate, otherwise
// Xiaomi's interconnect rejects the channel. Both read from CGMBand/keystore/.
val keystoreProps = Properties().apply {
    val f = rootProject.file("../keystore/keystore.properties")
    if (!f.exists()) {
        throw GradleException("Missing ${f.path} — the shared signing keystore is required")
    }
    f.inputStream().use { load(it) }
}

// Xiaomi's wearable SDK is not committed; tools/fetch_xms_sdk.sh downloads it from Xiaomi.
if (fileTree("libs") { include("*.aar") }.isEmpty) {
    throw GradleException("Missing Xiaomi wearable SDK in app/libs — run tools/fetch_xms_sdk.sh first")
}

android {
    namespace = "com.fch.cgmband"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fch.cgmband"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("shared") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    testImplementation("junit:junit:4.13.2")
}
