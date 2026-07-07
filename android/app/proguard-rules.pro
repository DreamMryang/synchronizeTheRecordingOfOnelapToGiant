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

# ===== DataStore Preferences 底层 protobuf-lite 靠反射按名找字段（value_ 等），字段名不可混淆 =====
# 缺失会在首次写入时崩溃：RuntimeException: Field value_ for ... not found（真机联调实测）
# datastore-preferences-core 是纯 jar 不携带 consumer 规则，须手动保留
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ===== security-crypto 传递依赖 Tink 引用的编译期注解（运行时不存在，安全忽略）=====
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Room / WorkManager / Compose / security-crypto(Tink) 均依赖各自 artifact 内置的 consumer 规则，无需重复声明
