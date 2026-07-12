import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// release 签名信息来自 local.properties（不入库）；密钥文件在工程根 keystore/（已被 android/.gitignore 排除）
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseKeystoreFile = rootProject.file("keystore/release-keystore.jks")
val hasReleaseSigning = releaseKeystoreFile.exists() &&
    localProperties.getProperty("KEYSTORE_STORE_PASSWORD") != null

val appVersionName = "1.0.0"

android {
    namespace = "com.dreammryang.onelaptogiant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dreammryang.onelaptogiant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = localProperties.getProperty("KEYSTORE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("KEYSTORE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("KEYSTORE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 与 release 共用签名，保证两种构建签名一致可覆盖安装；缺密钥环境回退默认 debug 签名
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 缺密钥/密码的环境（如他人机器）自动退化为未签名 release，不阻断构建
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// APK 产物命名：OnelapToGiant-v1.0.0-release.apk / -debug.apk
base {
    archivesName.set("OnelapToGiant-v$appVersionName")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.work.testing)
}
