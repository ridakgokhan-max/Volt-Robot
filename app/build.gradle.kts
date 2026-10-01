plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voltcu.robot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voltcu.robot"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.8.2"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    signingConfigs {
        create("volt") {
            storeFile = file("volt.keystore")
            storePassword = "voltrobot"
            keyAlias = "volt"
            keyPassword = "voltrobot"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("volt") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("volt")
        }
    }

    System.getenv("VOLT_NDK_VERSION")?.let { ndkVersion = it }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            System.getenv("VOLT_CMAKE_VERSION")?.let { version = it }
        }
    }

    androidResources { noCompress += "tflite" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // Yüz algılama - modeli uygulamanın içinde, internet gerektirmez
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Yüz tanıma (kim olduğunu bilme) - internetsiz
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // İnternetsiz Türkçe konuşma tanıma
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
