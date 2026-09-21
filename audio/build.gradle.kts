plugins {
    id("companion.library")
}

android {
    namespace = "de.perigon.companion.audio"
}

dependencies {
    implementation(project(":core"))
}
