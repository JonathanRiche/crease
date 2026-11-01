plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.crease.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.crease.launcher"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload build: signed with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
