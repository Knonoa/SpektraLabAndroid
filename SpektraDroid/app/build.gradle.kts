import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep the signing key and its passwords outside the repository. Release builds
// fail explicitly instead of silently shipping with Android's public debug key.
val releaseSigning = Properties().apply {
    System.getenv("SPEKTRALAB_SIGNING_PROPERTIES")?.takeIf { it.isNotBlank() }?.let { path ->
        file(path).reader(Charsets.UTF_8).use { load(it) }
    }
}
val buildingRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (buildingRelease) {
    require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !releaseSigning.getProperty(it).isNullOrBlank()
    }) { "Release signing requires SPEKTRALAB_SIGNING_PROPERTIES; see docs/RELEASING.md." }
}

android {
    namespace = "com.spektralab.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()

    defaultConfig {
        applicationId = "com.spektralab.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("test-proguard-rules.pro")
        externalNativeBuild {
            cmake {
                System.getenv("SPEKTRALAB_LIBRAW_SOURCE")?.takeIf { it.isNotBlank() }?.let {
                    arguments += "-DSPEKTRALAB_LIBRAW_SOURCE=$it"
                }
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigning.isNotEmpty()) {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // a debug build installs beside the release one (another signing key cannot update it)
            applicationIdSuffix = providers.gradleProperty("debugApplicationIdSuffix").orElse(".dev").get()
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = providers.gradleProperty("debugAppLabel").orElse("SpektraLab Dev").get()
        }
        release {
            manifestPlaceholders["appLabel"] = "SpektraLab"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // The capture page: CameraX (1.4 is the last line that builds on compileSdk 35 / AGP 8.5),
    // its Camera2 interop for the lenses, and the OEM extensions (HDR, night) the phone's own camera uses
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-extensions:1.4.2")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // AndroidX Test uses these annotations; include them when R8 checks tests.
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.18.0")
}
