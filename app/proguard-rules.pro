# WebRTC usa JNI: as classes são acessadas pelo código nativo.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Socket.IO / Engine.IO / OkHttp
-keep class io.socket.** { *; }
-dontwarn io.socket.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
