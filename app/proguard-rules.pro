# Retrofit, OkHttp, kotlinx.serialization, CameraX and ML Kit ship their own consumer rules; these are the extras.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, RuntimeVisibleAnnotations

# kotlinx.serialization: keep generated serializers for the app's @Serializable classes.
-keep,includedescriptorclasses class ng.name.nsikak.libaccession.**$$serializer { *; }
-keepclassmembers class ng.name.nsikak.libaccession.** {
    *** Companion;
}
-keepclasseswithmembers class ng.name.nsikak.libaccession.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ONNX Runtime (PaddleOCR) uses reflection/JNI: keep its classes, as its docs require for R8.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
