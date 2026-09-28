# NewPipeExtractor uses Rhino for YouTube player signatures.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Rhino's unused desktop bean/JIT adapters are unavailable on Android.
# NewPipe explicitly uses Context.setInterpretedMode(true).
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn jdk.dynalink.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
