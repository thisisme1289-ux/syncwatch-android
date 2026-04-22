# Socket.IO
-keep class io.socket.** { *; }
-keep class io.socket.client.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Retrofit + Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.syncwatch.model.** { *; }

# WebRTC
-keep class org.webrtc.** { *; }

# ExoPlayer / media3
-keep class androidx.media3.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
