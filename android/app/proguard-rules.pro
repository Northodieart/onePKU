# Keep kotlinx.serialization generated serializers
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
-keep,includedescriptorclasses class *$$serializer { *; }

# Keep serializable models
-keep @kotlinx.serialization.Serializable class * { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# OkHttp / OkIO
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jsoup
-dontwarn org.jsoup.**

# security-crypto 依赖的 Google Tink 引用了只在编译期存在的 errorprone 注解，
# 运行时不会被调用，但 R8 找不到类会直接判定构建失败。
-dontwarn com.google.errorprone.annotations.**
