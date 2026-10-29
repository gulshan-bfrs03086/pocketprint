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

# TSC's library (only present in a build given app/libs/tscsdk.jar). Four of its private
# fields are reached by reflection: the input stream, to throw away unread replies, and
# all four to hand it a socket PocketPrint connected itself on Android 12 and newer
# (TscPortHandover). R8 must neither rename nor remove them; the rule does nothing when
# the class is absent.
-keepclassmembers class com.example.tscdll.TSCActivity {
    java.io.InputStream InStream;
    java.io.OutputStream OutStream;
    android.bluetooth.BluetoothSocket btSocket;
    boolean IsConnected;
}
-dontwarn com.example.tscdll.**
-dontwarn ar.com.hjg.pngj.**
