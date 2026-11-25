# Keep kotlinx.serialization generated classes and serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.dr34m3rt.retailscaled.core.**$$serializer { *; }
-keepclassmembers class io.github.dr34m3rt.retailscaled.core.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.dr34m3rt.retailscaled.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
