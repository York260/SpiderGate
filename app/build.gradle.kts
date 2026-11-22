plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.york.gatekeeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.york.gatekeeper"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
    }

    // 固定簽章：之後的新版才能直接覆蓋安裝
    signingConfigs {
        create("fixed") {
            storeFile = file("gatekeeper.keystore")
            storePassword = "gatekeeper"
            keyAlias = "gatekeeper"
            keyPassword = "gatekeeper"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
