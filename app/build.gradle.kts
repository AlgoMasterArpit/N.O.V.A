import java.security.MessageDigest
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
    // Jar metadata the SDK's dependencies all ship; the app doesn't need any of it.
    packaging { resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST") }
}

// The 55 MB offline wake-word model is too big to push, so the first build downloads it (checksum-verified).
val voskModel = tasks.register("downloadVoskModel") {
    val dir = file("src/main/assets/model-en-in")
    outputs.dir(dir)
    onlyIf { !dir.resolve("uuid").exists() }
    doLast {
        val zip = layout.buildDirectory.file("vosk-model.zip").get().asFile.apply { parentFile.mkdirs() }
        uri("https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip").toURL().openStream()
            .use { input -> zip.outputStream().use { input.copyTo(it) } }
        val sha = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha == "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7") { "Vosk model checksum mismatch: $sha" }
        copy {
            from(zipTree(zip)) { eachFile { path = path.substringAfter('/') } }
            includeEmptyDirs = false
            into(dir)
        }
        dir.resolve("uuid").writeText("nova-en-in-0.4\n")
    }
}
tasks.named("preBuild") { dependsOn(voskModel) }

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.anthropic:anthropic-java:2.34.0")
    testImplementation("junit:junit:4.13.2")
}
