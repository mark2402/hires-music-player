plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hiresplayer"
    // API 36 is the newest stable SDK that still installs on Android 15 phones.
    // API 37 stamps compileSdkVersionCodename "17", which some installers reject.
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hiresplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }
    signingConfigs {
        create("release") {
            storeFile = file("keystore/hires-release.jks")
            storePassword = "hires-sideload"
            keyAlias = "hires"
            keyPassword = "hires-sideload"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
    }
}

// AGP's signer omits the JAR (v1) signature when minSdk is 24 or higher.
// Honor and other OEM installers still verify that signature and report
// "App not installed" when it is missing. Re-sign after packaging, telling
// apksigner the signature must also cover pre-24 devices. The manifest
// minSdk stays 26.
tasks.configureEach {
    if (name != "packageRelease") return@configureEach
    doLast {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Set ANDROID_HOME so the release APK can be signed with v1+v2+v3")
        val buildTools = File(sdk, "build-tools").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
            ?: error("Android build-tools not found under $sdk")
        val apksigner = File(buildTools, "apksigner")
        val apk = project.layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val keystore = project.file("keystore/hires-release.jks")
        val command = listOf(
            apksigner.absolutePath,
            "sign",
            "--ks", keystore.absolutePath,
            "--ks-key-alias", "hires",
            "--ks-pass", "pass:hires-sideload",
            "--key-pass", "pass:hires-sideload",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--min-sdk-version", "23",
            apk.absolutePath,
        )
        val exit = ProcessBuilder(command).inheritIO().start().waitFor()
        if (exit != 0) error("apksigner failed with exit code $exit")
    }
}

dependencies {
    implementation(project(":format-core"))
    implementation(project(":decoder-ffmpeg"))

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    // Compose 1.12 / navigation 2.10 / core 1.19 require compileSdk 37.
    // These are the newest releases that still compile against API 36.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("io.coil-kt.coil3:coil:3.5.0")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
