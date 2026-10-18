# https://developer.android.com/build/shrink-code
# Rules for NewPipeExtractor, taken from TeamNewPipe/NewPipe's own app/proguard-rules.pro.

# Keep stack traces readable: error details are shown to the user and pasted into bug reports.
-dontobfuscate

-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }

# Rhino (YouTube player JS for signature/throttling deobfuscation)
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**

# Protobuf-lite (used by the extractor for YouTube requests)
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

-dontwarn okhttp3.**
-dontwarn okio.**

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
