plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "fake.screenshot"
    compileSdk {
        version = release(37)
    }
    sourceSets {
        named("main") {
            jniLibs.directories.add("build/generated/jniLibs")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        dex {
            useLegacyPackaging = true
        }
    }
    defaultConfig {
        applicationId = "fake.screenshot"
        minSdk = 29
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.tink)
    implementation(libs.tink.android)
    implementation(libs.jsch)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.api)
    implementation(libs.provider)
    implementation(libs.service)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val buildScrcpy = tasks.register<Exec>("buildScrcpy") {
    description = "Build libscrcpy-server.so"
    workingDir = project.rootDir
    commandLine = listOf(
        if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew",
        ":scrcpy:assembleRelease"
    )
}

val injectScrcpyAsLib = tasks.register("injectScrcpyAsLib") {
    description = "Add scrcpy to ScreenshotFaker's jnilibs"
    dependsOn(buildScrcpy)

    val scrcpySo = project(":scrcpy").layout.buildDirectory
        .file("outputs/apk/release/libscrcpy-server.so")
        .get()
        .asFile
    val abiList = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    val targetBaseDir = layout.buildDirectory.dir("generated/jniLibs")
    val targetFiles = abiList.map { abi ->
        targetBaseDir.map { it.file("${abi}/libscrcpy-server.so") }
    }

    inputs.files(listOf(scrcpySo))
    outputs.files(targetFiles)

    doLast {
        if (!scrcpySo.exists()) {
            throw GradleException("Failed to find libscrcpy-server.so Path: ${scrcpySo.absolutePath}")
        }
        abiList.forEach { abi ->
            val targetBase = targetBaseDir.get().asFile
            val targetDir = file("${targetBase}/${abi}")
            targetDir.mkdirs()
            copy {
                from(scrcpySo)
                into(targetDir)
            }
        }
        println("Successfully added libscrcpy-server.so")
    }
}

tasks.named("preBuild") {
    dependsOn(injectScrcpyAsLib)
}