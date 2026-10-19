plugins {
    `kotlin-dsl`
}

group = "de.perigon.companion.buildlogic"

dependencies {
    // compileOnly: at runtime the root project's plugin classpath provides AGP.
    compileOnly(libs.android.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("companionLibrary") {
            id = "companion.library"
            implementationClass = "CompanionLibraryPlugin"
        }
    }
}
