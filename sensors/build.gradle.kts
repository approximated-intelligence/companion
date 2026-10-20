plugins {
    id("companion.library")
}

android {
    namespace = "de.perigon.companion.sensors"
}

dependencies {
    implementation(project(":core"))
}
