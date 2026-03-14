import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "de.perigon.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.perigon.companion"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        create("releaseDebuggable") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug") // still signed with debug key
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    androidResources {
        noCompress += "bin"
    }
    // aaptOptions {
    //     noCompress += "bin"
    // }
    // packaging {
    //     noCompress += "bin"
    // }
}

dependencies {
    // Compose
    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.lifecycle)
    implementation(libs.compose.viewmodel)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.workmanager)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)

    // Ktor
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // CameraX (embedded QR scanner)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Crypto
    implementation(libs.lazysodium.android) {
        artifact { type = "aar" }
        isTransitive = false
    }
    implementation(libs.jna) {
        artifact { type = "aar" }
        isTransitive = false
    }

    // Diff
    implementation(libs.diff.utils)

    // QR
    implementation(libs.zxing.core)

    // Drag-to-reorder
    implementation(libs.reorderable)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.exifinterface)
    implementation(libs.documentfile)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)

    implementation(libs.compose.material.icons)
}
