plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.personcounter"
    compileSdk = 34
    buildToolsVersion = "33.0.1"

    defaultConfig {
        applicationId = "com.example.personcounter"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    // Prevents the asset pipeline from compressing the TFLite model binary,
    // which would corrupt it and cause a runtime load failure.
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // CameraX — all components must share the same version
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // TensorFlow Lite Task Vision — high-level API for object detection
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    // GPU delegate for hardware-accelerated inference on modern devices
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.9.0")

    // ML Kit Face Detection — multi-face, fast mode
    implementation("com.google.mlkit:face-detection:16.1.7")
    // ML Kit Pose Detection — accurate single-person model (applied per person crop)
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta4")

    // Media3 ExoPlayer — RTSP IP camera streaming
    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
}
