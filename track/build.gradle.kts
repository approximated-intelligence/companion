plugins {
    id("companion.library")
}

android {
    namespace = "de.perigon.companion.track"
}

dependencies {
    implementation(project(":core"))

    implementation(libs.coil.compose)
}
