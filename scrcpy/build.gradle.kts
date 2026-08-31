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
            // 输出名中性化（隐蔽性）：APK 内库名与落地拷贝源路径均可见，
            // 不得含 scrcpy 等知名工具名
            output.outputFileName = "libextsvr.so"
        }
    }
}

dependencies {}