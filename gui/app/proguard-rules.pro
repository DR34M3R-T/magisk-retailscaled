# Keep kotlinx.serialization generated classes and serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.roottailscale.core.**$$serializer { *; }
-keepclassmembers class com.example.roottailscale.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.roottailscale.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
