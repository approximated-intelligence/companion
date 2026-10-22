plugins {
    id("companion.library")
}

android {
    namespace = "de.perigon.companion.backup"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":media"))

    implementation(libs.coil.compose)
    implementation(libs.lazysodium.android) {
        artifact { type = "aar" }
        isTransitive = false
    }
    implementation(libs.jna) {
        artifact { type = "aar" }
        isTransitive = false
    }
}
