# Vosk talks to its native library through JNA, which resolves classes by name.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
