plugins {
    id("companion.library")
}

android {
    namespace = "de.perigon.companion.media"
}

dependencies {
    implementation(project(":core"))

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.exifinterface)
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    implementation(libs.coil.compose)
}
