import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withGroovyBuilder

/**
 * Every feature module is the same kind of thing: an Android library with
 * Compose, Hilt, Room and WorkManager. One place for SDK levels, Java target
 * and the shared dependency set; module build files only add what is theirs.
 */
class CompanionLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.plugin.compose")
            apply("org.jetbrains.kotlin.plugin.serialization")
            apply("com.google.devtools.ksp")
            apply("com.google.dagger.hilt.android")
        }

        extensions.configure<LibraryExtension> {
            compileSdk = 37
            defaultConfig { minSdk = 34 }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
            buildFeatures { compose = true }
            sourceSets.getByName("main").kotlin.directories.addAll(listOf("src/kotlin"))
            lint {
                abortOnError = true
                lintConfig = rootProject.file("lint.xml")
            }
        }

        extensions.getByName("ksp").withGroovyBuilder {
            "arg"("room.schemaLocation", "$projectDir/schemas")
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun lib(alias: String) = libs.findLibrary(alias).get()

        with(dependencies) {
            add("implementation", platform(lib("compose-bom")))
            listOf(
                "compose-ui", "compose-material3", "compose-material-icons", "compose-activity",
                "compose-lifecycle", "compose-viewmodel", "compose-ui-tooling-preview",
                "navigation-compose", "hilt-android", "hilt-navigation-compose", "hilt-work",
                "room-runtime", "room-ktx", "workmanager", "core-ktx", "coroutines-android",
                "datastore-preferences",
            ).forEach { add("implementation", lib(it)) }
            add("ksp", lib("hilt-compiler"))
            add("ksp", lib("hilt-work-compiler"))
            add("ksp", lib("room-compiler"))
        }
    }
}
