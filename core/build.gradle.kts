plugins {
    id("companion.library")
}

android {
    namespace = "de.perigon.companion.core"
}

dependencies {
    // api: HttpClient and DocumentFile appear in core's public signatures.
    api(libs.ktor.client.android)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.json)
    api(libs.documentfile)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
}
