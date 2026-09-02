# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.gulshan.pocketprint.** {
    *** Companion;
}
-keepclasseswithmembers class com.gulshan.pocketprint.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
