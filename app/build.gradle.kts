import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        languageVersion = KotlinVersion.KOTLIN_2_4
        apiVersion = KotlinVersion.KOTLIN_2_4
    }
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
        minSdk = 30
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
    implementation(libs.apksig)
    implementation(libs.androidx.datastore.tink)
    implementation(libs.tink.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.jsch)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.api)
    implementation(libs.provider)
    implementation(libs.service)
    compileOnly(libs.libxposed.api)
    implementation(libs.hiddenapibypass)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val injectScrcpyAsLib = tasks.register("injectScrcpyAsLib") {
    description = "Add scrcpy to ScreenshotFaker's jnilibs"
    dependsOn(":scrcpy:packageRelease")

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
        // 防止打包过期 server：校验二进制内含当前源码的构建标记。
        // libscrcpy-server.so 实际是 APK（zip），标记字符串存在 dex 的
        // string pool 中。曾出现 APK 打包旧 server 导致控制失效且难以定位，
        // 此处构建期直接失败，避免问题遗留到运行时。
        val expectedMarker = "relay-v4"
        val markerFound = runCatching {
            ZipFile(scrcpySo).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".dex") }
                    .any { entry ->
                        zip.getInputStream(entry).use { input ->
                            // ISO-8859-1 字节↔字符一一对应，二进制无损；
                            // 标记为 ASCII，MUTF-8 下字节不变，直接子串搜索
                            String(input.readBytes(), Charsets.ISO_8859_1).contains(expectedMarker)
                        }
                    }
            }
        }.getOrDefault(false)
        if (!markerFound) {
            throw GradleException(
                "libscrcpy-server.so does not contain build marker '$expectedMarker'. " +
                        "The scrcpy server binary is stale. Run './gradlew :scrcpy:clean " +
                        ":scrcpy:packageRelease' and rebuild."
            )
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
        println("Successfully added libscrcpy-server.so (build marker: $expectedMarker)")
    }
}

tasks.named("preBuild") {
    dependsOn(injectScrcpyAsLib)
}