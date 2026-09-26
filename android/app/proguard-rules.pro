# SWITCHY ProGuard rules (ตอนนี้ยังไม่เปิด minify — เก็บไว้ให้ครบโครง)
-keep class com.switchy.intercom.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
# แพ็กเก็ตเสียงอ่านด้วยมือ (ByteBuffer) — ไม่มี reflection ให้กังวล
