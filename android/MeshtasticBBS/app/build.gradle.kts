plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.chaquo.python")
    id("kotlin-parcelize")   // needed for @Parcelize on DataPacket
}

val signingPropsFile = rootProject.file("signing/signing.properties")
val signingProps = if (signingPropsFile.exists()) {
    signingPropsFile.readLines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                null
            } else {
                val key = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=").trim()
                key to value
            }
        }
        .toMap()
} else {
    emptyMap()
}

android {
    namespace  = "com.meshtastic.bbs"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.meshtastic.bbs"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 1
        versionName   = "b0603a"
    }

    flavorDimensions += "mode"
    productFlavors {
        create("client") {
            dimension = "mode"
            manifestPlaceholders["appLabel"] = "MeshBBS"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"
            buildConfigField("boolean", "SERVER_BUILD", "false")
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        create("server") {
            dimension = "mode"
            applicationIdSuffix = ".server"
            manifestPlaceholders["appLabel"] = "MeshServer"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_server"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_server_round"
            buildConfigField("boolean", "SERVER_BUILD", "true")
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    signingConfigs {
        create("meshbbsRelease") {
            if (signingPropsFile.exists()) {
                storeFile = rootProject.file(signingProps.getValue("storeFile"))
                storePassword = signingProps.getValue("storePassword")
                keyAlias = signingProps.getValue("keyAlias")
                keyPassword = signingProps.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("meshbbsRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        aidl   = true   // required to compile IMeshService.aidl
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("py", "-3.13")
        pyc {
            src = false
        }
    }
}

tasks.configureEach {
    if (name.contains("Client") && name.contains("Python")) {
        enabled = false
    }
}

dependencies {
    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    debugImplementation(libs.compose.ui.tooling)
}
