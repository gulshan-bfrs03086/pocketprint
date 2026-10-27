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

# TSC's library (only present in a build given app/libs/tscsdk.jar). Its private input
# stream is reached by reflection to throw away unread replies, so R8 must neither rename
# nor remove it; the rule does nothing when the class is absent.
-keepclassmembers class com.example.tscdll.TSCActivity {
    java.io.InputStream InStream;
}
-dontwarn com.example.tscdll.**
-dontwarn ar.com.hjg.pngj.**
