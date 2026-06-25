# Ravilo — R8 rules
# Most consumer rules come from library AAR manifests (Compose, Ktor, Coil, Media3).
# Only add rules here that those libraries don't provide themselves.

# kotlinx.serialization: keep generated serializer companions
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class dev.jellystructure.**$$serializer { *; }
-keepclassmembers class dev.jellystructure.** {
    @kotlinx.serialization.SerialName <fields>;
}
