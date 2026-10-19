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
    compileSdk = 37

    defaultConfig {
        applicationId = "de.perigon.companion"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.3"
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
        // R8-minified like release, debug-signed. Currently not debuggable;
        // flip isDebuggable to true to attach a debugger to the minified build.
        // Library modules only have debug/release.
        create("releaseDebuggable") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        lintConfig = rootProject.file("lint.xml")
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
}

dependencies {
    // The app shell: features chosen here are the features of this app.
    implementation(project(":core"))
    implementation(project(":media"))
    implementation(project(":backup"))
    implementation(project(":posts"))
    implementation(project(":track"))
    implementation(project(":audio"))

    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.lifecycle)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    implementation(libs.workmanager)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
}
