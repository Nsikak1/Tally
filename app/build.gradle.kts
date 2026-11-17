import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import ng.name.nsikak.tally.build.GenerateDeltaUpdateTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val propVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 9
val propVersionName = project.findProperty("versionName")?.toString() ?: "0.8.0"

android {
    namespace = "ng.name.nsikak.libaccession"
    compileSdk = 35

    defaultConfig {
        applicationId = "ng.name.nsikak.libaccession"
        minSdk = 25
        targetSdk = 35
        versionCode = propVersionCode
        versionName = propVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the shared debug key so it installs over debug builds.
            signingConfig = signingConfigs.getByName("debug")
            // Release only: debug keeps every ABI for Waydroid and the emulator.
            // armeabi-v7a = 32-bit ARM (your Termux device). For 64-bit tablets use "arm64-v8a",
            // or list both to run on either at the cost of a bigger download.
            ndk {
                abiFilters += listOf("armeabi-v7a")
            }
        }
    }

    androidResources {
        localeFilters += listOf("en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
           // "/META-INF/*.version",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
        )
        // Compresses native libraries inside the APK (smaller download, slightly larger install).
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Camera + on-device barcode model (bundled: works offline, no Play Services download)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.onnxruntime.android)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Local cache of looked-up books
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation("junit:junit:4.13.2")
}

tasks.register("generateDeltaUpdate", GenerateDeltaUpdateTask::class.java) {
    dependsOn("assembleRelease")
    description = "Generates BSDiff delta update patches and update-manifest.json for release builds."
    group = "publishing"

    targetVersionCode = android.defaultConfig.versionCode?.toLong()
    targetVersionName = android.defaultConfig.versionName
}

tasks.register("publishDeltaUpdate") {
    dependsOn("generateDeltaUpdate")
    description = "Alias for generateDeltaUpdate."
    group = "publishing"
}
