# Add project specific ProGuard rules here.
# Keep moshi / retrofit models
-keep class com.smsgateway.** { *; }
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class *
-keepclassmembers,allowshrinking,allowobfuscation class * {
  @com.squareup.moshi.Json <fields>;
}
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
