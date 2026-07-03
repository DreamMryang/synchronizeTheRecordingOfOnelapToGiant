# ===== kotlinx.serialization（官方模板规则）=====
# 保住 @Serializable 类的生成序列化器与 Companion.serializer()，否则运行时反射查找会失败
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.dreammryang.onelaptogiant.**$$serializer { *; }
-keepclassmembers class com.dreammryang.onelaptogiant.** {
    *** Companion;
}
-keepclasseswithmembers class com.dreammryang.onelaptogiant.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== OkHttp 平台探测类的无害告警 =====
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ===== security-crypto 传递依赖 Tink 引用的编译期注解（运行时不存在，安全忽略）=====
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Room / WorkManager / Compose / security-crypto(Tink) 均依赖各自 artifact 内置的 consumer 规则，无需重复声明
