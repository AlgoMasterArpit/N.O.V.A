import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build outside OneDrive: syncing locks files mid-build and uploads hundreds of MB for nothing.
System.getenv("LOCALAPPDATA")?.let { layout.buildDirectory.set(File(it, "nova-build")) }

val keys = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }

android {
    namespace = "com.arpit.nova"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.arpit.nova"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" } // all modern Samsung/Pixel/Moto phones
    }
    // Release build (not debuggable) so nobody can pull NOVA's notes off the phone over USB.
    signingConfigs {
        create("release") {
            storeFile = keys.getProperty("storeFile")?.let(::file)
            storePassword = keys.getProperty("password")
            keyAlias = "nova"
            keyPassword = keys.getProperty("password")
        }
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.anthropic:anthropic-java:2.34.0")
    testImplementation("junit:junit:4.13.2")
}
