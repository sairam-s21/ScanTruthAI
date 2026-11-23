import java.io.File
import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.scantruth.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scantruth.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val storeFilePath = keystoreProperties.getProperty("storeFile") ?: "upload-keystore.jks"
                val rawFile = File(storeFilePath)
                storeFile = if (rawFile.isAbsolute) {
                    rawFile
                } else {
                    rootProject.file(storeFilePath).absoluteFile
                }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: "upload"
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Debug signing remains completely untouched (uses standard Android debug keystore)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("com.google.firebase.ai.type.PublicPreviewAPI")
    }
}

dependencies {
    // Firebase BoM v35.2.0
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    // Firebase AI Logic on-device backend (not managed by the BoM; pin explicitly)
    implementation("com.google.firebase:firebase-ai-ondevice:16.0.0-beta08")

    // Stage 1: On-Device OCR (ML Kit Text Recognition)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // CameraX — live preview + still capture for the Scan screen
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // Reads EXIF orientation so captured/gallery stills are not shown sideways
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Google Sign-In for Firebase Auth: Credential Manager + Google ID token
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Loads the signed-in user's Google profile photo from its https URL
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Typed parsing/validation of every AI response
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Unit tests for the deterministic/privacy/parsing layers
    testImplementation("junit:junit:4.13.2")

    // Jetpack Compose + Coroutines
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
