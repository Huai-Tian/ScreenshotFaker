plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "fake.screenshot.scrcpy"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "fake.screenshot.scrcpy"
        minSdk = 21
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        aidl = true
    }

    buildTypes {
        release {
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName = "libscrcpy-server.so"
        }
    }
}

dependencies {}