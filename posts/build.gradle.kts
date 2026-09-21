plugins {
    id("companion.library")
}

android {
    namespace = "de.perigon.companion.posts"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":media"))

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.reorderable)
    implementation(libs.diff.utils)
}
