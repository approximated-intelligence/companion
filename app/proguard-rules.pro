# kage
-keep class com.github.androidpasswordstore.kage.**

# Ktor
-keep class io.ktor.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class de.perigon.companion.**$$serializer { *; }
-keepclassmembers class de.perigon.companion.** { *** Companion; }
-keepclasseswithmembers class de.perigon.companion.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ZXing
-keep class com.google.zxing.**

-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
