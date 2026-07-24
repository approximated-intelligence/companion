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

# JNA (lazysodium needs the Java bridge intact for JNI reflection)
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }

# Lazysodium
-keep class com.goterl.lazysodium.** { *; }

# WorkManager workers (instantiated reflectively by class name)
-keep class de.perigon.companion.**.worker.** { *; }

# JNA AWT references (not available on Android)
-dontwarn java.awt.**
-dontwarn com.sun.jna.Native$AWT
