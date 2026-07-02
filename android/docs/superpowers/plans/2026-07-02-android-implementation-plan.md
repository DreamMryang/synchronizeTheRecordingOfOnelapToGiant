# Android 端（顽鹿 → 捷安特同步 App）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按[已确认设计](../specs/2026-07-02-android-migration-design.md)从零实现 Android App：自动把顽鹿运动的骑行 FIT 文件同步到捷安特骑行，以捷安特服务端 `all_upload` 为唯一去重事实源。

**Architecture:** 单模块 MVVM——`ui/`（Compose 三屏 + ViewModel）、`data/`（Room 展示层数据库、OkHttp 接口客户端、DataStore/EncryptedSharedPreferences 配置）、`sync/`（SyncEngine 纯逻辑核心 + SyncWorker 调度接入）。SyncEngine 与触发方式解耦，全局 Mutex 防并发。

**Tech Stack:** Kotlin、Jetpack Compose + Material 3、Room、WorkManager、OkHttp、kotlinx.serialization、DataStore + EncryptedSharedPreferences、Timber；测试 JUnit4 + Robolectric + MockWebServer + kotlinx-coroutines-test。

## Global Constraints

- 项目根为 `android/`（Android Studio 直接打开此目录），单模块 `:app`；包名 `com.dreammryang.onelaptogiant`。
- minSdk **26** / targetSdk **35** / compileSdk **35**；JDK 17。
- 技术栈家族不可替换（如不得用 Retrofit 替 OkHttp、Moshi 替 kotlinx.serialization、Hilt 替手工 DI）；具体版本号以 Task 1 的 `libs.versions.toml` 为基线，构建报错时允许按 Android Studio 提示微调版本号。
- 顽鹿签名密钥为代码常量：`fe9f8382418fcdeb136461cac6acae7b`。
- 捷安特上传固定参数：`device=bike_computer`、`brand=onelap`；处理成功状态字面量：`"成功"`。
- 去重只认捷安特服务端 `all_upload`（每会话调用**一次**）；本地 Room 仅作展示/记账，**不参与去重判定**。
- 默认配置：同步天数 30、同步间隔 6 小时、任意网络；间隔可选项 1/3/6/12/24 小时。
- FIT 文件目录固定 `context.filesDir/fit/`；上传成功后文件保留不删。
- 单元测试命令（在 `android/` 目录下执行）：`./gradlew :app:testDebugUnitTest`。
- 认证失效判定（HTTP 401/403、响应无有效 data / status 异常）各集中在**一处函数**，错误码以联调抓包为准；若联调发现契约变化，**先改 `docs/api/` 再改代码**（仓库跨端约定）。
- 提交信息中文、`type(android): 描述` 格式。**本仓库要求 commit 前征得用户同意**：开始执行本计划前，先向用户申请「按任务逐个提交」的一次性授权；未获授权则跳过各任务的提交步骤，改为在检查点由用户确认后统一提交。
- 与用户沟通、代码内注释均用简体中文；注释精简，只写代码本身表达不了的约束。

## 文件结构总览（全部位于 `android/`）

```
settings.gradle.kts / build.gradle.kts / gradle.properties / gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
app/src/main/java/com/dreammryang/onelaptogiant/
  App.kt                                 Application：Timber 初始化、持有 AppContainer
  MainActivity.kt                        入口 Activity，setContent { AppNav }
  di/AppContainer.kt                     手工 DI 容器（lazy 装配全部依赖）
  ui/theme/Theme.kt                      AppTheme（M3 明暗色）
  ui/common/StatusText.kt                状态枚举 → 中文文案/颜色
  ui/common/Formats.kt                   时间戳格式化
  ui/AppNav.kt                           底部导航 + NavHost
  ui/home/HomeViewModel.kt / HomeScreen.kt
  ui/history/HistoryViewModel.kt / HistoryScreen.kt
  ui/history/SessionDetailViewModel.kt / SessionDetailScreen.kt
  ui/settings/SettingsViewModel.kt / SettingsScreen.kt
  data/db/Entities.kt                    SyncSessionEntity / SyncRecordEntity / 三个枚举
  data/db/SyncSessionDao.kt / SyncRecordDao.kt / AppDatabase.kt
  data/settings/SettingsRepository.kt    DataStore：天数/间隔/网络约束
  data/auth/SecurePrefs.kt               EncryptedSharedPreferences 工厂
  data/auth/TokenStore.kt                token 持久化（按平台）
  data/auth/CredentialStore.kt           账号密码持久化（改账号清对应 token）
  data/auth/TokenManager.kt              懒登录 + 401 续登重试一次；AuthFailedException / LoginFailedException
  data/network/HttpClientProvider.kt     OkHttpClient / Json 单例
  data/network/onelap/OnelapSign.kt      MD5 / nonce / sign 签名算法
  data/network/onelap/OnelapClient.kt    接口（SyncEngine 依赖的抽象）
  data/network/onelap/OnelapApi.kt       实现 + DTO
  data/network/giant/GiantClient.kt      接口 + AllUploadSummary
  data/network/giant/GiantApi.kt         实现 + DTO + buildSummary
  sync/SyncLogic.kt                      reconcile 决策 + 会话状态归纳（纯函数）
  sync/SyncEngine.kt                     核心流程 + retryRecord + 进度 Flow + Mutex
  sync/SyncWorker.kt                     WorkManager 接入
  sync/SyncScheduler.kt                  周期任务注册 + 手动触发
app/src/test/java/com/dreammryang/onelaptogiant/   全部单元测试（含 Robolectric）
```

## 执行前置

- JDK 17、Android SDK Platform 35 已安装（Android Studio 自带）。
- 生成 gradle wrapper：`android/` 下执行 `gradle wrapper --gradle-version 8.11.1`（需本机装有任意版本 gradle）；若无本机 gradle，用 Android Studio 打开 `android/` 时会提示生成，或从任一现有项目拷贝 `gradle/wrapper/` 与 `gradlew`、`gradlew.bat` 后改 `gradle-wrapper.properties` 的版本号。
- 以下所有 `./gradlew` 命令都在 `android/` 目录下执行（Windows 下用 `.\gradlew.bat`，后文统一简写）。

---

### Task 1: 项目脚手架（可构建、可跑测试的空壳 App）

**Files:**
- Create: `android/.gitignore`
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/App.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/di/AppContainer.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/MainActivity.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/theme/Theme.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/SmokeTest.kt`

**Interfaces:**
- Consumes: 无（起点任务）
- Produces: 可构建工程；`class App : Application`（属性 `val container: AppContainer`）；`class AppContainer(app: Application)`（空壳，后续任务填充）；`AppTheme(content: @Composable () -> Unit)`；版本目录别名 `libs.*` 供全部后续任务引用。

- [ ] **Step 1: 写入构建配置文件**

`android/.gitignore`：

```
.gradle/
build/
local.properties
.idea/
*.iml
.kotlin/
captures/
```

`android/settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "OnelapToGiant"
include(":app")
```

`android/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

`android/gradle.properties`：

```
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`android/gradle/libs.versions.toml`：

```toml
[versions]
agp = "8.10.1"
kotlin = "2.1.21"
ksp = "2.1.21-2.0.1"
composeBom = "2025.05.00"
activityCompose = "1.10.1"
navigationCompose = "2.9.0"
lifecycle = "2.9.0"
coreKtx = "1.16.0"
room = "2.7.1"
work = "2.10.1"
okhttp = "4.12.0"
serialization = "1.8.1"
datastore = "1.1.6"
securityCrypto = "1.1.0-alpha07"
timber = "5.0.1"
junit = "4.13.2"
robolectric = "4.14.1"
androidxTestCore = "1.6.1"
coroutinesTest = "1.10.2"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
security-crypto = { module = "androidx.security:security-crypto", version.ref = "securityCrypto" }
timber = { module = "com.jakewharton.timber:timber", version.ref = "timber" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core-ktx", version.ref = "androidxTestCore" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutinesTest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

`android/app/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dreammryang.onelaptogiant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dreammryang.onelaptogiant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
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
```

- [ ] **Step 2: 写入 Manifest、资源与最小代码**

`android/app/src/main/AndroidManifest.xml`（图标暂用系统默认图标，避免引入二进制资源；后续需要时再换）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:name=".App"
        android:label="@string/app_name"
        android:icon="@android:drawable/sym_def_app_icon"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/res/values/strings.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">顽鹿同步</string>
</resources>
```

`App.kt`：

```kotlin
package com.dreammryang.onelaptogiant

import android.app.Application
import com.dreammryang.onelaptogiant.di.AppContainer
import timber.log.Timber

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        container = AppContainer(this)
    }
}
```

`di/AppContainer.kt`（空壳，Task 11 完整装配）：

```kotlin
package com.dreammryang.onelaptogiant.di

import android.app.Application

class AppContainer(private val app: Application)
```

`ui/theme/Theme.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}
```

`MainActivity.kt`（占位界面，Task 15 换成导航）：

```kotlin
package com.dreammryang.onelaptogiant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.dreammryang.onelaptogiant.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Text("Onelap → Giant")
            }
        }
    }
}
```

- [ ] **Step 3: 写冒烟测试**

`android/app/src/test/java/com/dreammryang/onelaptogiant/SmokeTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant

import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeTest {
    @Test
    fun `构建与测试环境可用`() {
        assertTrue(true)
    }
}
```

- [ ] **Step 4: 生成 wrapper 并验证构建**

```bash
cd android
gradle wrapper --gradle-version 8.11.1   # 或用「执行前置」中的替代方式
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，SmokeTest 1 个测试通过。若版本解析失败，按错误提示微调 `libs.versions.toml` 中版本号（Global Constraints 允许）。

- [ ] **Step 5: Commit（需用户已授权，见 Global Constraints）**

```bash
git add android/
git commit -m "feat(android): 初始化 Android 工程脚手架（Compose + Room + WorkManager 依赖就绪）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 顽鹿签名算法 OnelapSign（与桌面版对拍）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/network/onelap/OnelapSign.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/data/network/onelap/OnelapSignTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `object OnelapSign`——`const val SIGN_KEY: String`；`fun md5Hex(input: String): String`（32 位小写十六进制）；`fun nonce(uuid: String = ...): String`（UUID 去横线取后 16 位）；`fun sign(account: String, passwordMd5: String, nonce: String, timestamp: Long): String`。Task 6 的 OnelapApi 使用。

参考对拍源：`desktop/src/main/java/**/service/OnelapService.java`（桌面版签名实现）；契约见 [docs/api/onelap.md §1](../../../../docs/api/onelap.md)。下方期望值已用独立工具预先算好，可直接信任。

- [ ] **Step 1: 写失败测试**

`OnelapSignTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.network.onelap

import org.junit.Assert.assertEquals
import org.junit.Test

class OnelapSignTest {

    @Test
    fun `md5Hex 输出 32 位小写十六进制`() {
        assertEquals("32250170a0dca92d53ec9624f336ca24", OnelapSign.md5Hex("pass123"))
    }

    @Test
    fun `nonce 为 UUID 去横线后取末 16 位`() {
        assertEquals(
            "a716446655440000",
            OnelapSign.nonce("550e8400-e29b-41d4-a716-446655440000"),
        )
    }

    @Test
    fun `nonce 无参时随机且长度 16`() {
        assertEquals(16, OnelapSign.nonce().length)
    }

    @Test
    fun `sign 拼串顺序与桌面版一致`() {
        // 期望值 = md5("account=13800138000&nonce=abcdef0123456789&password=32250170a0dca92d53ec9624f336ca24&timestamp=1751443200&key=fe9f8382418fcdeb136461cac6acae7b")
        val sign = OnelapSign.sign(
            account = "13800138000",
            passwordMd5 = OnelapSign.md5Hex("pass123"),
            nonce = "abcdef0123456789",
            timestamp = 1751443200L,
        )
        assertEquals("d68287cd4b8b6d4af22adf1691029ea7", sign)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.OnelapSignTest"`
Expected: FAIL，编译错误 `Unresolved reference: OnelapSign`。

- [ ] **Step 3: 实现**

`OnelapSign.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.network.onelap

import java.security.MessageDigest
import java.util.UUID

object OnelapSign {
    const val SIGN_KEY = "fe9f8382418fcdeb136461cac6acae7b"

    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun nonce(uuid: String = UUID.randomUUID().toString()): String =
        uuid.replace("-", "").takeLast(16)

    fun sign(account: String, passwordMd5: String, nonce: String, timestamp: Long): String =
        md5Hex("account=$account&nonce=$nonce&password=$passwordMd5&timestamp=$timestamp&key=$SIGN_KEY")
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.OnelapSignTest"`
Expected: PASS，4 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/data/network/onelap/OnelapSign.kt \
        android/app/src/test/java/com/dreammryang/onelaptogiant/data/network/onelap/OnelapSignTest.kt
git commit -m "feat(android): 顽鹿登录签名算法（MD5/nonce/sign，与桌面版对拍）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Room 数据层（会话表 + 记录表，仅展示层）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/db/Entities.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/db/SyncSessionDao.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/db/SyncRecordDao.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/db/AppDatabase.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/data/db/SyncDaoTest.kt`

**Interfaces:**
- Consumes: 无
- Produces（后续任务大量引用，签名以此为准）:
  - `enum class TriggerType { AUTO, MANUAL }`
  - `enum class SessionStatus { RUNNING, SUCCESS, PARTIAL, FAILED, NO_NEW }`
  - `enum class RecordStatus { DOWNLOADED, DOWNLOAD_FAILED, SYNCED, UPLOAD_FAILED, PROCESS_FAILED }`
  - `data class SyncSessionEntity(id: Long = 0, triggerType: TriggerType, status: SessionStatus, startedAt: Long, finishedAt: Long? = null, foundCount: Int = 0, downloadedCount: Int = 0, syncedCount: Int = 0, failedCount: Int = 0, errorMsg: String? = null)`
  - `data class SyncRecordEntity(id: Long = 0, fitUrl: String, activityId: String? = null, sessionId: Long, status: RecordStatus, fileSize: Long? = null, errorMsg: String? = null, downloadTime: Long? = null, syncTime: Long? = null, createdAt: Long, updatedAt: Long)`
  - `SyncSessionDao`: `suspend fun insert(session): Long`、`suspend fun update(session)`、`suspend fun getById(id: Long): SyncSessionEntity?`、`fun observeAll(): Flow<List<SyncSessionEntity>>`、`fun observeLatestFinished(): Flow<SyncSessionEntity?>`
  - `SyncRecordDao`: `suspend fun insert(record): Long`、`suspend fun update(record)`、`suspend fun getById(id: Long): SyncRecordEntity?`、`suspend fun getByFitUrl(fitUrl: String): SyncRecordEntity?`、`suspend fun getReconcilable(): List<SyncRecordEntity>`、`fun observeBySession(sessionId: Long): Flow<List<SyncRecordEntity>>`、`fun observeProcessFailedCount(): Flow<Int>`
  - `abstract class AppDatabase : RoomDatabase`：`fun sessionDao()`、`fun recordDao()`

- [ ] **Step 1: 写失败测试**

`SyncDaoTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() = db.close()

    private fun session(status: SessionStatus = SessionStatus.RUNNING) = SyncSessionEntity(
        triggerType = TriggerType.AUTO, status = status, startedAt = 1000L,
    )

    private fun record(fitUrl: String, sessionId: Long, status: RecordStatus) = SyncRecordEntity(
        fitUrl = fitUrl, sessionId = sessionId, status = status,
        createdAt = 1000L, updatedAt = 1000L,
    )

    @Test
    fun `会话插入更新与倒序查询`() = runTest {
        val id1 = db.sessionDao().insert(session())
        val id2 = db.sessionDao().insert(session().copy(startedAt = 2000L))

        val s1 = db.sessionDao().getById(id1)!!
        db.sessionDao().update(s1.copy(status = SessionStatus.SUCCESS, finishedAt = 1500L, syncedCount = 3))

        val all = db.sessionDao().observeAll().first()
        assertEquals(listOf(id2, id1), all.map { it.id })
        assertEquals(SessionStatus.SUCCESS, all[1].status)
        assertEquals(3, all[1].syncedCount)
    }

    @Test
    fun `observeLatestFinished 跳过 RUNNING 会话`() = runTest {
        assertNull(db.sessionDao().observeLatestFinished().first())
        val id1 = db.sessionDao().insert(session(SessionStatus.SUCCESS))
        db.sessionDao().insert(session(SessionStatus.RUNNING).copy(startedAt = 9999L))
        assertEquals(id1, db.sessionDao().observeLatestFinished().first()?.id)
    }

    @Test
    fun `记录按 fitUrl 查询与状态更新`() = runTest {
        val sid = db.sessionDao().insert(session())
        db.recordDao().insert(record("a.fit", sid, RecordStatus.DOWNLOADED))

        val found = db.recordDao().getByFitUrl("a.fit")
        assertNotNull(found)
        db.recordDao().update(found!!.copy(status = RecordStatus.SYNCED, syncTime = 2000L))
        assertEquals(RecordStatus.SYNCED, db.recordDao().getByFitUrl("a.fit")!!.status)
        assertNull(db.recordDao().getByFitUrl("missing.fit"))
    }

    @Test
    fun `getReconcilable 只返回 SYNCED 与 UPLOAD_FAILED`() = runTest {
        val sid = db.sessionDao().insert(session())
        db.recordDao().insert(record("a.fit", sid, RecordStatus.SYNCED))
        db.recordDao().insert(record("b.fit", sid, RecordStatus.UPLOAD_FAILED))
        db.recordDao().insert(record("c.fit", sid, RecordStatus.DOWNLOAD_FAILED))
        db.recordDao().insert(record("d.fit", sid, RecordStatus.PROCESS_FAILED))

        assertEquals(setOf("a.fit", "b.fit"), db.recordDao().getReconcilable().map { it.fitUrl }.toSet())
    }

    @Test
    fun `按会话查记录与处理失败计数`() = runTest {
        val sid1 = db.sessionDao().insert(session())
        val sid2 = db.sessionDao().insert(session())
        db.recordDao().insert(record("a.fit", sid1, RecordStatus.PROCESS_FAILED))
        db.recordDao().insert(record("b.fit", sid2, RecordStatus.SYNCED))

        assertEquals(listOf("a.fit"), db.recordDao().observeBySession(sid1).first().map { it.fitUrl })
        assertEquals(1, db.recordDao().observeProcessFailedCount().first())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SyncDaoTest"`
Expected: FAIL，编译错误（实体/DAO 未定义）。

- [ ] **Step 3: 实现**

`Entities.kt`（Room ≥2.3 原生支持枚举按 name 存 TEXT，无需 TypeConverter）：

```kotlin
package com.dreammryang.onelaptogiant.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TriggerType { AUTO, MANUAL }

enum class SessionStatus { RUNNING, SUCCESS, PARTIAL, FAILED, NO_NEW }

enum class RecordStatus { DOWNLOADED, DOWNLOAD_FAILED, SYNCED, UPLOAD_FAILED, PROCESS_FAILED }

@Entity(tableName = "sync_session")
data class SyncSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trigger_type") val triggerType: TriggerType,
    val status: SessionStatus,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "found_count") val foundCount: Int = 0,
    @ColumnInfo(name = "downloaded_count") val downloadedCount: Int = 0,
    @ColumnInfo(name = "synced_count") val syncedCount: Int = 0,
    @ColumnInfo(name = "failed_count") val failedCount: Int = 0,
    @ColumnInfo(name = "error_msg") val errorMsg: String? = null,
)

@Entity(tableName = "sync_record", indices = [Index(value = ["fit_url"], unique = true)])
data class SyncRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "fit_url") val fitUrl: String,
    @ColumnInfo(name = "activity_id") val activityId: String? = null,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val status: RecordStatus,
    @ColumnInfo(name = "file_size") val fileSize: Long? = null,
    @ColumnInfo(name = "error_msg") val errorMsg: String? = null,
    @ColumnInfo(name = "download_time") val downloadTime: Long? = null,
    @ColumnInfo(name = "sync_time") val syncTime: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

`SyncSessionDao.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncSessionDao {
    @Insert
    suspend fun insert(session: SyncSessionEntity): Long

    @Update
    suspend fun update(session: SyncSessionEntity)

    @Query("SELECT * FROM sync_session WHERE id = :id")
    suspend fun getById(id: Long): SyncSessionEntity?

    @Query("SELECT * FROM sync_session ORDER BY started_at DESC, id DESC")
    fun observeAll(): Flow<List<SyncSessionEntity>>

    @Query("SELECT * FROM sync_session WHERE status != 'RUNNING' ORDER BY started_at DESC, id DESC LIMIT 1")
    fun observeLatestFinished(): Flow<SyncSessionEntity?>
}
```

`SyncRecordDao.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRecordDao {
    @Insert
    suspend fun insert(record: SyncRecordEntity): Long

    @Update
    suspend fun update(record: SyncRecordEntity)

    @Query("SELECT * FROM sync_record WHERE id = :id")
    suspend fun getById(id: Long): SyncRecordEntity?

    @Query("SELECT * FROM sync_record WHERE fit_url = :fitUrl")
    suspend fun getByFitUrl(fitUrl: String): SyncRecordEntity?

    @Query("SELECT * FROM sync_record WHERE status IN ('SYNCED', 'UPLOAD_FAILED')")
    suspend fun getReconcilable(): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_record WHERE session_id = :sessionId ORDER BY id")
    fun observeBySession(sessionId: Long): Flow<List<SyncRecordEntity>>

    @Query("SELECT COUNT(*) FROM sync_record WHERE status = 'PROCESS_FAILED'")
    fun observeProcessFailedCount(): Flow<Int>
}
```

`AppDatabase.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SyncSessionEntity::class, SyncRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SyncSessionDao
    abstract fun recordDao(): SyncRecordDao
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SyncDaoTest"`
Expected: PASS，5 个测试通过。（若 Robolectric 报 SDK 35 不支持，在测试类上加 `@org.robolectric.annotation.Config(sdk = [34])`。）

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/data/db/ \
        android/app/src/test/java/com/dreammryang/onelaptogiant/data/db/
git commit -m "feat(android): Room 数据层（会话/记录表与 DAO，仅展示层不参与去重）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 配置与凭证存储（DataStore + EncryptedSharedPreferences）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/settings/SettingsRepository.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/auth/SecurePrefs.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/auth/TokenStore.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/auth/CredentialStore.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/data/settings/SettingsRepositoryTest.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/data/auth/CredentialStoreTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class Platform { ONELAP, GIANT }`
  - `class TokenStore(prefs: SharedPreferences)`: `fun get(platform: Platform): String?`、`fun set(platform: Platform, token: String)`、`fun clear(platform: Platform)`
  - `class CredentialStore(prefs: SharedPreferences, tokenStore: TokenStore)`: 属性 `onelapAccount/onelapPassword/giantUsername/giantPassword: String?`；`fun saveOnelap(account, password)`、`fun saveGiant(username, password)`（保存即清对应平台 token）；`fun isConfigured(): Boolean`；`val configured: StateFlow<Boolean>`
  - `fun createSecurePrefs(context: Context): SharedPreferences`（生产用加密实现；测试注入普通 SharedPreferences）
  - `class SettingsRepository(dataStore: DataStore<Preferences>)`: `val recentDays: Flow<Int>`（默认 30）、`val intervalHours: Flow<Int>`（默认 6）、`val wifiOnly: Flow<Boolean>`（默认 false）；`suspend fun setRecentDays(Int)` / `setIntervalHours(Int)` / `setWifiOnly(Boolean)`；顶层扩展 `val Context.settingsDataStore: DataStore<Preferences>`
  - 常量 `val INTERVAL_OPTIONS = listOf(1, 3, 6, 12, 24)`（定义于 SettingsRepository.kt 顶层）

- [ ] **Step 1: 写失败测试**

`SettingsRepositoryTest.kt`（纯 JVM，不需要 Robolectric）：

```kotlin
package com.dreammryang.onelaptogiant.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun repo(): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFile("settings_test.preferences_pb")
        }
        return SettingsRepository(dataStore)
    }

    @After
    fun teardown() = scope.cancel()

    @Test
    fun `默认值 30 天 6 小时 任意网络`() = runBlocking {
        val repo = repo()
        assertEquals(30, repo.recentDays.first())
        assertEquals(6, repo.intervalHours.first())
        assertFalse(repo.wifiOnly.first())
    }

    @Test
    fun `写入后可读回`() = runBlocking {
        val repo = repo()
        repo.setRecentDays(7)
        repo.setIntervalHours(12)
        repo.setWifiOnly(true)
        assertEquals(7, repo.recentDays.first())
        assertEquals(12, repo.intervalHours.first())
        assertTrue(repo.wifiOnly.first())
    }
}
```

`CredentialStoreTest.kt`（Robolectric，用普通 SharedPreferences 注入，不测加密本身）：

```kotlin
package com.dreammryang.onelaptogiant.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialStoreTest {
    private lateinit var tokenStore: TokenStore
    private lateinit var store: CredentialStore

    @Before
    fun setup() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_secure", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenStore = TokenStore(prefs)
        store = CredentialStore(prefs, tokenStore)
    }

    @Test
    fun `token 按平台读写清除`() {
        tokenStore.set(Platform.ONELAP, "t1")
        tokenStore.set(Platform.GIANT, "t2")
        assertEquals("t1", tokenStore.get(Platform.ONELAP))
        tokenStore.clear(Platform.ONELAP)
        assertNull(tokenStore.get(Platform.ONELAP))
        assertEquals("t2", tokenStore.get(Platform.GIANT))
    }

    @Test
    fun `保存顽鹿账号只清顽鹿 token`() {
        tokenStore.set(Platform.ONELAP, "t1")
        tokenStore.set(Platform.GIANT, "t2")
        store.saveOnelap("user", "pwd")
        assertNull(tokenStore.get(Platform.ONELAP))
        assertEquals("t2", tokenStore.get(Platform.GIANT))
        assertEquals("user", store.onelapAccount)
        assertEquals("pwd", store.onelapPassword)
    }

    @Test
    fun `四项齐全才算已配置且 configured 流同步更新`() {
        assertFalse(store.isConfigured())
        assertFalse(store.configured.value)
        store.saveOnelap("a", "b")
        assertFalse(store.isConfigured())
        store.saveGiant("c", "d")
        assertTrue(store.isConfigured())
        assertTrue(store.configured.value)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SettingsRepositoryTest" --tests "*.CredentialStoreTest"`
Expected: FAIL，编译错误（类未定义）。

- [ ] **Step 3: 实现**

`SettingsRepository.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

val INTERVAL_OPTIONS = listOf(1, 3, 6, 12, 24)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val RECENT_DAYS = intPreferencesKey("sync_recent_days")
        val INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")
        val WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
    }

    val recentDays: Flow<Int> = dataStore.data.map { it[Keys.RECENT_DAYS] ?: 30 }
    val intervalHours: Flow<Int> = dataStore.data.map { it[Keys.INTERVAL_HOURS] ?: 6 }
    val wifiOnly: Flow<Boolean> = dataStore.data.map { it[Keys.WIFI_ONLY] ?: false }

    suspend fun setRecentDays(days: Int) {
        dataStore.edit { it[Keys.RECENT_DAYS] = days }
    }

    suspend fun setIntervalHours(hours: Int) {
        dataStore.edit { it[Keys.INTERVAL_HOURS] = hours }
    }

    suspend fun setWifiOnly(wifiOnly: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY] = wifiOnly }
    }
}
```

`SecurePrefs.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

fun createSecurePrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        "secure_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
```

`TokenStore.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.auth

import android.content.SharedPreferences

enum class Platform { ONELAP, GIANT }

class TokenStore(private val prefs: SharedPreferences) {
    private fun key(platform: Platform) = "token_${platform.name.lowercase()}"

    fun get(platform: Platform): String? = prefs.getString(key(platform), null)

    fun set(platform: Platform, token: String) {
        prefs.edit().putString(key(platform), token).apply()
    }

    fun clear(platform: Platform) {
        prefs.edit().remove(key(platform)).apply()
    }
}
```

`CredentialStore.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.auth

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CredentialStore(
    private val prefs: SharedPreferences,
    private val tokenStore: TokenStore,
) {
    val onelapAccount: String? get() = prefs.getString(KEY_ONELAP_ACCOUNT, null)
    val onelapPassword: String? get() = prefs.getString(KEY_ONELAP_PASSWORD, null)
    val giantUsername: String? get() = prefs.getString(KEY_GIANT_USERNAME, null)
    val giantPassword: String? get() = prefs.getString(KEY_GIANT_PASSWORD, null)

    private val _configured = MutableStateFlow(isConfigured())
    val configured: StateFlow<Boolean> = _configured

    fun saveOnelap(account: String, password: String) {
        prefs.edit()
            .putString(KEY_ONELAP_ACCOUNT, account)
            .putString(KEY_ONELAP_PASSWORD, password)
            .apply()
        tokenStore.clear(Platform.ONELAP)
        _configured.value = isConfigured()
    }

    fun saveGiant(username: String, password: String) {
        prefs.edit()
            .putString(KEY_GIANT_USERNAME, username)
            .putString(KEY_GIANT_PASSWORD, password)
            .apply()
        tokenStore.clear(Platform.GIANT)
        _configured.value = isConfigured()
    }

    fun isConfigured(): Boolean =
        !onelapAccount.isNullOrBlank() && !onelapPassword.isNullOrBlank() &&
            !giantUsername.isNullOrBlank() && !giantPassword.isNullOrBlank()

    private companion object {
        const val KEY_ONELAP_ACCOUNT = "onelap_account"
        const val KEY_ONELAP_PASSWORD = "onelap_password"
        const val KEY_GIANT_USERNAME = "giant_username"
        const val KEY_GIANT_PASSWORD = "giant_password"
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SettingsRepositoryTest" --tests "*.CredentialStoreTest"`
Expected: PASS，5 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/data/settings/ \
        android/app/src/main/java/com/dreammryang/onelaptogiant/data/auth/ \
        android/app/src/test/java/com/dreammryang/onelaptogiant/data/settings/ \
        android/app/src/test/java/com/dreammryang/onelaptogiant/data/auth/
git commit -m "feat(android): 配置与凭证存储（DataStore + 加密偏好，改账号清对应 token）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: TokenManager（懒登录 + 认证失效自动续登重试一次）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/auth/TokenManager.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/data/auth/TokenManagerTest.kt`

**Interfaces:**
- Consumes: `TokenStore`、`Platform`（Task 4）
- Produces:
  - `class AuthFailedException(message: String, cause: Throwable? = null) : Exception`——接口客户端判定认证失效时抛出（Task 6/7），TokenManager 捕获后续登重试
  - `class LoginFailedException(message: String) : Exception`——登录接口本身失败（不触发续登，向上传播为流程级失败）
  - `class TokenManager(platform: Platform, tokenStore: TokenStore, login: suspend () -> String)`: `suspend fun getToken(): String`（缓存命中不登录）、`fun invalidate()`、`suspend fun <T> withAuthRetry(block: suspend (token: String) -> T): T`（块抛 `AuthFailedException` → 清缓存 → 重登 → 重试一次，仍失败则传播）

- [ ] **Step 1: 写失败测试**

`TokenManagerTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TokenManagerTest {
    private lateinit var tokenStore: TokenStore
    private var loginCount = 0

    @Before
    fun setup() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_tm", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenStore = TokenStore(prefs)
        loginCount = 0
    }

    private fun manager(login: suspend () -> String = { loginCount++; "token-$loginCount" }) =
        TokenManager(Platform.ONELAP, tokenStore, login)

    @Test
    fun `有缓存 token 时不登录`() = runTest {
        tokenStore.set(Platform.ONELAP, "cached")
        assertEquals("cached", manager().getToken())
        assertEquals(0, loginCount)
    }

    @Test
    fun `无缓存时登录一次并缓存`() = runTest {
        val m = manager()
        assertEquals("token-1", m.getToken())
        assertEquals("token-1", m.getToken())
        assertEquals(1, loginCount)
        assertEquals("token-1", tokenStore.get(Platform.ONELAP))
    }

    @Test
    fun `认证失败时清缓存续登并重试一次`() = runTest {
        tokenStore.set(Platform.ONELAP, "stale")
        var calls = 0
        val result = manager().withAuthRetry { token ->
            calls++
            if (token == "stale") throw AuthFailedException("401")
            "ok-with-$token"
        }
        assertEquals("ok-with-token-1", result)
        assertEquals(2, calls)
        assertEquals(1, loginCount)
    }

    @Test
    fun `续登后仍认证失败则向上传播`() = runTest {
        tokenStore.set(Platform.ONELAP, "stale")
        assertThrows(AuthFailedException::class.java) {
            kotlinx.coroutines.runBlocking {
                manager().withAuthRetry<String> { throw AuthFailedException("401") }
            }
        }
        assertEquals(1, loginCount)
    }

    @Test
    fun `非认证异常不触发续登`() = runTest {
        tokenStore.set(Platform.ONELAP, "cached")
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                manager().withAuthRetry<String> { error("网络炸了") }
            }
        }
        assertEquals(0, loginCount)
        assertEquals("cached", tokenStore.get(Platform.ONELAP))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TokenManagerTest"`
Expected: FAIL，编译错误（TokenManager 未定义）。

- [ ] **Step 3: 实现**

`TokenManager.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class AuthFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LoginFailedException(message: String) : Exception(message)

class TokenManager(
    private val platform: Platform,
    private val tokenStore: TokenStore,
    private val login: suspend () -> String,
) {
    private val mutex = Mutex()

    suspend fun getToken(): String = mutex.withLock {
        tokenStore.get(platform) ?: login().also { tokenStore.set(platform, it) }
    }

    fun invalidate() = tokenStore.clear(platform)

    suspend fun <T> withAuthRetry(block: suspend (token: String) -> T): T {
        val token = getToken()
        return try {
            block(token)
        } catch (e: AuthFailedException) {
            Timber.i("%s 认证失效，重新登录后重试一次: %s", platform, e.message)
            invalidate()
            block(getToken())
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TokenManagerTest"`
Expected: PASS，5 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/data/auth/TokenManager.kt \
        android/app/src/test/java/com/dreammryang/onelaptogiant/data/auth/TokenManagerTest.kt
git commit -m "feat(android): TokenManager 登录态缓存（懒登录 + 失效续登重试一次）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 顽鹿接口客户端 OnelapApi（登录/列表/详情/下载 + 续登集成）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/network/HttpClientProvider.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/network/onelap/OnelapClient.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/network/onelap/OnelapApi.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/data/network/onelap/OnelapApiTest.kt`

**Interfaces:**
- Consumes: `OnelapSign`（Task 2）；`AuthFailedException`、`LoginFailedException`、`TokenManager`（Task 5）
- Produces:
  - `object HttpClientProvider`: `val client: OkHttpClient`（10s 连接 / 60s 读写超时）、`val json: Json`（`ignoreUnknownKeys + isLenient`）
  - `interface OnelapClient`（SyncEngine 依赖的抽象）:
    - `suspend fun listActivityIds(token: String, startDate: String, endDate: String): List<String>`
    - `suspend fun fetchFitUrl(token: String, activityId: String): String?`（无 FIT 的活动返回 null）
    - `suspend fun downloadFit(token: String, fitUrl: String, targetDir: File): File`
  - `class OnelapApi(client, json, loginBaseUrl = "https://www.onelap.cn", apiBaseUrl = "https://u.onelap.cn", nonceProvider, timestampProvider) : OnelapClient`，另有 `suspend fun login(account: String, passwordPlain: String): String`（供 TokenManager 的 login 闭包用，不在接口上）

接口契约：[docs/api/onelap.md](../../../../docs/api/onelap.md)。fitUrl 的 Base64 用 `java.util.Base64.getEncoder()`（标准编码，与桌面版一致）；若联调发现 URL 内 `+`/`/` 引发问题，改 `getUrlEncoder()` 并更新契约文档。

- [ ] **Step 1: 写失败测试**

`OnelapApiTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.network.onelap

import com.dreammryang.onelaptogiant.data.auth.AuthFailedException
import com.dreammryang.onelaptogiant.data.auth.LoginFailedException
import com.dreammryang.onelaptogiant.data.network.HttpClientProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

// Robolectric 仅为最后的续登集成测试提供 SharedPreferences；与 MockWebServer 兼容
@RunWith(RobolectricTestRunner::class)
class OnelapApiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: OnelapApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        api = OnelapApi(
            client = HttpClientProvider.client,
            json = HttpClientProvider.json,
            loginBaseUrl = base,
            apiBaseUrl = base,
            nonceProvider = { "abcdef0123456789" },
            timestampProvider = { 1751443200L },
        )
    }

    @After
    fun teardown() = server.shutdown()

    @Test
    fun `登录携带正确签名三件套并返回 token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"token":"tok-1"}]}"""))

        val token = api.login("13800138000", "pass123")

        assertEquals("tok-1", token)
        val req = server.takeRequest()
        assertEquals("/api/login", req.path)
        assertEquals("abcdef0123456789", req.getHeader("nonce"))
        assertEquals("1751443200", req.getHeader("timestamp"))
        assertEquals("d68287cd4b8b6d4af22adf1691029ea7", req.getHeader("sign"))
        assertTrue(req.body.readUtf8().contains("\"password\":\"32250170a0dca92d53ec9624f336ca24\""))
    }

    @Test
    fun `登录 data 为空抛 LoginFailedException`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertThrows(LoginFailedException::class.java) {
            runBlocking { api.login("a", "b") }
        }
    }

    @Test
    fun `活动列表两阶段查询 total 大于首页时二次全量拉取`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"pagination":{"total":3},"list":[{"id":101},{"id":102}]}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"pagination":{"total":3},"list":[{"id":101},{"id":102},{"id":103}]}}"""
            )
        )

        val ids = api.listActivityIds("tok", "2026-06-01", "2026-07-01")

        assertEquals(listOf("101", "102", "103"), ids)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"limit\":20"))
        assertTrue(second.contains("\"limit\":3"))
    }

    @Test
    fun `活动列表 total 不超过首页时只请求一次`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"pagination":{"total":1},"list":[{"id":7}]}}"""
            )
        )
        assertEquals(listOf("7"), api.listActivityIds("tok", "2026-06-01", "2026-07-01"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `详情返回 fitUrl 或 null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"ridingRecord":{"fitUrl":"a.fit"}}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"ridingRecord":{"fitUrl":""}}}"""))
        assertEquals("a.fit", api.fetchFitUrl("tok", "101"))
        assertNull(api.fetchFitUrl("tok", "102"))
    }

    @Test
    fun `下载按 Base64 路径写入目标文件`() = runBlocking {
        server.enqueue(MockResponse().setBody("FITDATA"))
        val dir = tmp.newFolder("fit")

        val file = api.downloadFit("tok", "ride_2026_0618.fit", dir)

        assertEquals("ride_2026_0618.fit", file.name)
        assertEquals("FITDATA", file.readText())
        val expectedPath = "/api/otm/ride_record/analysis/fit_content/" +
            Base64.getEncoder().encodeToString("ride_2026_0618.fit".toByteArray())
        assertEquals(expectedPath, server.takeRequest().path)
    }

    @Test
    fun `HTTP 401 抛 AuthFailedException`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(AuthFailedException::class.java) {
            runBlocking { api.fetchFitUrl("stale", "101") }
        }
    }

    @Test
    fun `配合 TokenManager 实现 401 续登后重试`() = runBlocking {
        // 场景：缓存 token 失效 → 详情 401 → 自动登录 → 重试成功（设计 §4 / 测试策略「网络测试」）
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("test_onelap_it", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val tokenStore = com.dreammryang.onelaptogiant.data.auth.TokenStore(prefs)
        tokenStore.set(com.dreammryang.onelaptogiant.data.auth.Platform.ONELAP, "stale")
        val manager = com.dreammryang.onelaptogiant.data.auth.TokenManager(
            com.dreammryang.onelaptogiant.data.auth.Platform.ONELAP, tokenStore,
        ) { api.login("13800138000", "pass123") }

        server.enqueue(MockResponse().setResponseCode(401))                       // 旧 token 请求
        server.enqueue(MockResponse().setBody("""{"data":[{"token":"fresh"}]}""")) // 自动续登
        server.enqueue(MockResponse().setBody("""{"data":{"ridingRecord":{"fitUrl":"a.fit"}}}""")) // 重试

        val fitUrl = manager.withAuthRetry { token -> api.fetchFitUrl(token, "101") }

        assertEquals("a.fit", fitUrl)
        assertEquals(3, server.requestCount)
        server.takeRequest() // 401 的那次
        server.takeRequest() // 登录
        assertEquals("fresh", server.takeRequest().getHeader("Authorization"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.OnelapApiTest"`
Expected: FAIL，编译错误（OnelapApi/HttpClientProvider 未定义）。

- [ ] **Step 3: 实现**

`HttpClientProvider.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientProvider {
    // all_upload 可达数千条、上传为整批多文件，读写超时放宽到 60s
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
```

`OnelapClient.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.network.onelap

import java.io.File

interface OnelapClient {
    suspend fun listActivityIds(token: String, startDate: String, endDate: String): List<String>
    suspend fun fetchFitUrl(token: String, activityId: String): String?
    suspend fun downloadFit(token: String, fitUrl: String, targetDir: File): File
}
```

`OnelapApi.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.network.onelap

import com.dreammryang.onelaptogiant.data.auth.AuthFailedException
import com.dreammryang.onelaptogiant.data.auth.LoginFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Base64

@Serializable
data class OnelapLoginResponse(val data: List<OnelapLoginData>? = null)

@Serializable
data class OnelapLoginData(val token: String? = null)

@Serializable
data class RideListResponse(val data: RideListData? = null)

@Serializable
data class RideListData(val pagination: RidePagination? = null, val list: List<RideItem>? = null)

@Serializable
data class RidePagination(val total: Int = 0)

@Serializable
data class RideItem(val id: Long)

@Serializable
data class RideDetailResponse(val data: RideDetailData? = null)

@Serializable
data class RideDetailData(val ridingRecord: RidingRecord? = null)

@Serializable
data class RidingRecord(val fitUrl: String? = null)

class OnelapApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val loginBaseUrl: String = "https://www.onelap.cn",
    private val apiBaseUrl: String = "https://u.onelap.cn",
    private val nonceProvider: () -> String = { OnelapSign.nonce() },
    private val timestampProvider: () -> Long = { System.currentTimeMillis() / 1000 },
) : OnelapClient {

    suspend fun login(account: String, passwordPlain: String): String = withContext(Dispatchers.IO) {
        val passwordMd5 = OnelapSign.md5Hex(passwordPlain)
        val nonce = nonceProvider()
        val timestamp = timestampProvider()
        val body = buildJsonObject {
            put("account", account)
            put("password", passwordMd5)
        }
        val request = Request.Builder()
            .url("$loginBaseUrl/api/login")
            .header("nonce", nonce)
            .header("timestamp", timestamp.toString())
            .header("sign", OnelapSign.sign(account, passwordMd5, nonce, timestamp))
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = runCatching { json.decodeFromString<OnelapLoginResponse>(text) }.getOrNull()
            parsed?.data?.firstOrNull()?.token
                ?: throw LoginFailedException("顽鹿登录失败: HTTP ${resp.code}, body=$text")
        }
    }

    override suspend fun listActivityIds(
        token: String,
        startDate: String,
        endDate: String,
    ): List<String> = withContext(Dispatchers.IO) {
        // 惯用两阶段：先 limit=20 取 total，total 更大时再全量取回
        val first = queryList(token, limit = 20, startDate, endDate)
        val total = first.pagination?.total ?: 0
        val items = if (total > (first.list?.size ?: 0)) {
            queryList(token, limit = total, startDate, endDate).list
        } else {
            first.list
        }
        (items ?: emptyList()).map { it.id.toString() }
    }

    private fun queryList(token: String, limit: Int, startDate: String, endDate: String): RideListData {
        val body = buildJsonObject {
            put("page", 1)
            put("limit", limit)
            put("start_date", startDate)
            put("end_date", endDate)
        }
        val request = Request.Builder()
            .url("$apiBaseUrl/api/otm/ride_record/list")
            .header("Authorization", token)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            ensureAuthorized(resp)
            val parsed = json.decodeFromString<RideListResponse>(resp.body?.string().orEmpty())
            return parsed.data ?: throw AuthFailedException("活动列表响应无 data，视为认证失效")
        }
    }

    override suspend fun fetchFitUrl(token: String, activityId: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$apiBaseUrl/api/otm/ride_record/analysis/$activityId")
                .header("Authorization", token)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                ensureAuthorized(resp)
                val parsed = json.decodeFromString<RideDetailResponse>(resp.body?.string().orEmpty())
                val data = parsed.data ?: throw AuthFailedException("活动详情响应无 data，视为认证失效")
                data.ridingRecord?.fitUrl?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun downloadFit(token: String, fitUrl: String, targetDir: File): File =
        withContext(Dispatchers.IO) {
            val encoded = Base64.getEncoder().encodeToString(fitUrl.toByteArray(Charsets.UTF_8))
            val request = Request.Builder()
                .url("$apiBaseUrl/api/otm/ride_record/analysis/fit_content/$encoded")
                .header("Authorization", token)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                ensureAuthorized(resp)
                if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}: $fitUrl")
                targetDir.mkdirs()
                val target = File(targetDir, fitUrl)
                resp.body!!.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }
        }

    // 顽鹿认证失效判定集中处；错误码以联调抓包为准（docs/api/onelap.md 末节）
    private fun ensureAuthorized(resp: Response) {
        if (resp.code == 401 || resp.code == 403) {
            throw AuthFailedException("顽鹿认证失效: HTTP ${resp.code}")
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.OnelapApiTest"`
Expected: PASS，8 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/data/network/ \
        android/app/src/test/java/com/dreammryang/onelaptogiant/data/network/
git commit -m "feat(android): 顽鹿接口客户端（签名登录/两阶段列表/详情/FIT 下载 + 401 续登）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: 捷安特接口客户端 GiantApi（登录/all_upload 集合构建/整批上传）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/network/giant/GiantClient.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/data/network/giant/GiantApi.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/data/network/giant/GiantApiTest.kt`

**Interfaces:**
- Consumes: `HttpClientProvider`（Task 6）、`AuthFailedException`/`LoginFailedException`（Task 5）
- Produces:
  - `data class AllUploadSummary(val uploaded: Set<String>, val failedProcess: Map<String, String>)`——`uploaded`=出现过任意状态的文件名全集（去重用）；`failedProcess`=无任何一条成功的文件名 → 展示用错误信息（标红用）
  - `interface GiantClient`:
    - `suspend fun fetchAllUpload(token: String): AllUploadSummary`
    - `suspend fun uploadFits(token: String, files: List<File>): Boolean`（整批语义：true=status==1）
  - `class GiantApi(client, json, baseUrl = "https://ridelife.giant.com.cn") : GiantClient`，另有 `suspend fun login(username: String, password: String): String`（不在接口上）；`companion object` 内 `const val STATUS_SUCCESS = "成功"` 与 `fun buildSummary(records: List<UploadRecord>): AllUploadSummary`
  - `@Serializable data class UploadRecord(val file: String = "", val status: String = "", val msg: String? = null)`

接口契约：[docs/api/giant.md](../../../../docs/api/giant.md)。判定约定（集中在 `ensureAuthorized` + `fetchAllUpload`，联调后可调）：HTTP 401/403 → `AuthFailedException`；`all_upload` 响应 `status != 1` → `AuthFailedException`（该接口仅带 token 参数，异常≈token 失效）；`upload_fit` 响应 `status != 1` → 返回 false（同一会话内 token 刚被 all_upload 验证过，视为业务失败而非认证失败）。

- [ ] **Step 1: 写失败测试**

`GiantApiTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.network.giant

import com.dreammryang.onelaptogiant.data.auth.AuthFailedException
import com.dreammryang.onelaptogiant.data.auth.LoginFailedException
import com.dreammryang.onelaptogiant.data.network.HttpClientProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GiantApiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: GiantApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = GiantApi(
            client = HttpClientProvider.client,
            json = HttpClientProvider.json,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun teardown() = server.shutdown()

    @Test
    fun `登录表单提交并返回 user_token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"user_token":"gt-1"}"""))
        assertEquals("gt-1", api.login("user", "pwd"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("username=user"))
        assertTrue(body.contains("password=pwd"))
    }

    @Test
    fun `登录无 user_token 抛 LoginFailedException`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"msg":"账号或密码错误"}"""))
        assertThrows(LoginFailedException::class.java) {
            runBlocking { api.login("user", "wrong") }
        }
    }

    @Test
    fun `buildSummary 同名多条任一成功即成功`() {
        val records = listOf(
            UploadRecord(file = "a.fit", status = "失败", msg = "解析出错"),
            UploadRecord(file = "a.fit", status = "成功", msg = "success"),
            UploadRecord(file = "b.fit", status = "失败", msg = "文件损坏"),
            UploadRecord(file = "c.fit", status = "成功", msg = "success"),
        )
        val summary = GiantApi.buildSummary(records)
        assertEquals(setOf("a.fit", "b.fit", "c.fit"), summary.uploaded)
        assertEquals(setOf("b.fit"), summary.failedProcess.keys)
        assertTrue(summary.failedProcess["b.fit"]!!.contains("文件损坏"))
    }

    @Test
    fun `fetchAllUpload 解析契约样例`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"status":1,"data":[
                  {"msg":"success","file":"MAGENE_C416_2026-06-18-17-51-08_768485_1781794983851.fit",
                   "status":"成功","time":"2026-06-19 00:00:11","brand":"onelap","device":"bike_computer"}
                ]}
                """.trimIndent()
            )
        )
        val summary = api.fetchAllUpload("gt-1")
        assertEquals(setOf("MAGENE_C416_2026-06-18-17-51-08_768485_1781794983851.fit"), summary.uploaded)
        assertTrue(summary.failedProcess.isEmpty())
        assertTrue(server.takeRequest().body.readUtf8().contains("token=gt-1"))
    }

    @Test
    fun `fetchAllUpload status 异常抛 AuthFailedException`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":0}"""))
        assertThrows(AuthFailedException::class.java) {
            runBlocking { api.fetchAllUpload("stale") }
        }
    }

    @Test
    fun `整批上传携带全部固定字段与文件`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":1}"""))
        val f1 = tmp.newFile("a.fit").apply { writeText("AAA") }
        val f2 = tmp.newFile("b.fit").apply { writeText("BBB") }

        assertTrue(api.uploadFits("gt-1", listOf(f1, f2)))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("name=\"token\""))
        assertTrue(body.contains("gt-1"))
        assertTrue(body.contains("name=\"device\""))
        assertTrue(body.contains("bike_computer"))
        assertTrue(body.contains("name=\"brand\""))
        assertTrue(body.contains("onelap"))
        assertTrue(body.contains("name=\"files[]\"; filename=\"a.fit\""))
        assertTrue(body.contains("name=\"files[]\"; filename=\"b.fit\""))
    }

    @Test
    fun `上传 status 非 1 返回 false`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":0}"""))
        val f = tmp.newFile("a.fit").apply { writeText("AAA") }
        assertFalse(api.uploadFits("gt-1", listOf(f)))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.GiantApiTest"`
Expected: FAIL，编译错误（GiantApi 未定义）。

- [ ] **Step 3: 实现**

`GiantClient.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.network.giant

import java.io.File

data class AllUploadSummary(
    val uploaded: Set<String>,
    val failedProcess: Map<String, String>,
)

interface GiantClient {
    suspend fun fetchAllUpload(token: String): AllUploadSummary
    suspend fun uploadFits(token: String, files: List<File>): Boolean
}
```

`GiantApi.kt`：

```kotlin
package com.dreammryang.onelaptogiant.data.network.giant

import com.dreammryang.onelaptogiant.data.auth.AuthFailedException
import com.dreammryang.onelaptogiant.data.auth.LoginFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File

@Serializable
data class GiantLoginResponse(@SerialName("user_token") val userToken: String? = null)

@Serializable
data class AllUploadResponse(val status: Int = 0, val data: List<UploadRecord>? = null)

@Serializable
data class UploadRecord(val file: String = "", val status: String = "", val msg: String? = null)

@Serializable
data class UploadFitResponse(val status: Int = 0)

class GiantApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://ridelife.giant.com.cn",
) : GiantClient {

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()
        val request = Request.Builder().url("$baseUrl/index.php/api/login").post(form).build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val token = runCatching { json.decodeFromString<GiantLoginResponse>(text).userToken }.getOrNull()
            token?.takeIf { it.isNotBlank() }
                ?: throw LoginFailedException("捷安特登录失败: HTTP ${resp.code}, body=$text")
        }
    }

    override suspend fun fetchAllUpload(token: String): AllUploadSummary = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("token", token).build()
        val request = Request.Builder().url("$baseUrl/index.php/api/all_upload").post(form).build()
        client.newCall(request).execute().use { resp ->
            ensureAuthorized(resp)
            val parsed = json.decodeFromString<AllUploadResponse>(resp.body?.string().orEmpty())
            // 该接口仅带 token 参数，status 异常≈token 失效（联调后如有更细错误码再调整）
            if (parsed.status != 1) throw AuthFailedException("all_upload status=${parsed.status}，视为认证失效")
            buildSummary(parsed.data ?: emptyList())
        }
    }

    override suspend fun uploadFits(token: String, files: List<File>): Boolean = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("token", token)
            .addFormDataPart("device", "bike_computer")
            .addFormDataPart("brand", "onelap")
        files.forEach { builder.addFormDataPart("files[]", it.name, it.asRequestBody(FIT_MEDIA)) }
        val request = Request.Builder().url("$baseUrl/index.php/api/upload_fit").post(builder.build()).build()
        client.newCall(request).execute().use { resp ->
            ensureAuthorized(resp)
            val parsed = runCatching {
                json.decodeFromString<UploadFitResponse>(resp.body?.string().orEmpty())
            }.getOrNull()
            parsed?.status == 1
        }
    }

    // 捷安特认证失效判定集中处；错误码以联调抓包为准（docs/api/giant.md 末节）
    private fun ensureAuthorized(resp: Response) {
        if (resp.code == 401 || resp.code == 403) {
            throw AuthFailedException("捷安特认证失效: HTTP ${resp.code}")
        }
    }

    companion object {
        const val STATUS_SUCCESS = "成功"
        private val FIT_MEDIA = "application/octet-stream".toMediaType()

        // 同名多条：任一成功即成功；全部非成功 → 处理失败（取最后一条的状态+消息作展示文案）
        fun buildSummary(records: List<UploadRecord>): AllUploadSummary {
            val byFile = records.filter { it.file.isNotBlank() }.groupBy { it.file }
            val failed = byFile
                .filterValues { list -> list.none { it.status == STATUS_SUCCESS } }
                .mapValues { (_, list) ->
                    val last = list.last()
                    listOfNotNull(last.status.takeIf { it.isNotBlank() }, last.msg).joinToString(": ")
                }
            return AllUploadSummary(uploaded = byFile.keys, failedProcess = failed)
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.GiantApiTest"`
Expected: PASS，7 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/data/network/giant/ \
        android/app/src/test/java/com/dreammryang/onelaptogiant/data/network/giant/
git commit -m "feat(android): 捷安特接口客户端（登录/all_upload 去重集合/整批上传）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: SyncLogic 纯函数（reconcile 决策 + 会话状态归纳）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncLogic.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/sync/SyncLogicTest.kt`

**Interfaces:**
- Consumes: `RecordStatus`/`SessionStatus`（Task 3）、`AllUploadSummary`（Task 7）
- Produces: `object SyncLogic`——
  - `fun reconcile(current: RecordStatus, fitUrl: String, summary: AllUploadSummary): RecordStatus?`（null=不变；仅对 SYNCED/UPLOAD_FAILED 生效）
  - `fun sessionStatus(foundCount: Int, failedCount: Int): SessionStatus`（found==0→NO_NEW；failed==0→SUCCESS；否则 PARTIAL。注：全部失败也归 PARTIAL——流程本身完整跑完，与流程级 FAILED 区分；FAILED 由 SyncEngine 的异常分支直接设置，不经此函数）

- [ ] **Step 1: 写失败测试**

`SyncLogicTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.sync

import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncLogicTest {
    private val summary = AllUploadSummary(
        uploaded = setOf("ok.fit", "bad.fit"),
        failedProcess = mapOf("bad.fit" to "失败: 解析出错"),
    )

    @Test
    fun `UPLOAD_FAILED 但服务端已成功则校正为 SYNCED`() {
        assertEquals(
            RecordStatus.SYNCED,
            SyncLogic.reconcile(RecordStatus.UPLOAD_FAILED, "ok.fit", summary),
        )
    }

    @Test
    fun `服务端有记录但全部非成功则校正为 PROCESS_FAILED`() {
        assertEquals(
            RecordStatus.PROCESS_FAILED,
            SyncLogic.reconcile(RecordStatus.SYNCED, "bad.fit", summary),
        )
        assertEquals(
            RecordStatus.PROCESS_FAILED,
            SyncLogic.reconcile(RecordStatus.UPLOAD_FAILED, "bad.fit", summary),
        )
    }

    @Test
    fun `状态已一致或服务端无记录时返回 null 不变`() {
        assertNull(SyncLogic.reconcile(RecordStatus.SYNCED, "ok.fit", summary))
        assertNull(SyncLogic.reconcile(RecordStatus.UPLOAD_FAILED, "not-on-server.fit", summary))
    }

    @Test
    fun `非 SYNCED-UPLOAD_FAILED 状态不参与 reconcile`() {
        assertNull(SyncLogic.reconcile(RecordStatus.DOWNLOADED, "ok.fit", summary))
        assertNull(SyncLogic.reconcile(RecordStatus.DOWNLOAD_FAILED, "bad.fit", summary))
        assertNull(SyncLogic.reconcile(RecordStatus.PROCESS_FAILED, "bad.fit", summary))
    }

    @Test
    fun `会话状态归纳`() {
        assertEquals(SessionStatus.NO_NEW, SyncLogic.sessionStatus(foundCount = 0, failedCount = 0))
        assertEquals(SessionStatus.SUCCESS, SyncLogic.sessionStatus(foundCount = 3, failedCount = 0))
        assertEquals(SessionStatus.PARTIAL, SyncLogic.sessionStatus(foundCount = 3, failedCount = 1))
        assertEquals(SessionStatus.PARTIAL, SyncLogic.sessionStatus(foundCount = 3, failedCount = 3))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SyncLogicTest"`
Expected: FAIL，编译错误（SyncLogic 未定义）。

- [ ] **Step 3: 实现**

`SyncLogic.kt`：

```kotlin
package com.dreammryang.onelaptogiant.sync

import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary

object SyncLogic {

    fun reconcile(current: RecordStatus, fitUrl: String, summary: AllUploadSummary): RecordStatus? {
        if (current != RecordStatus.SYNCED && current != RecordStatus.UPLOAD_FAILED) return null
        val target = when {
            summary.failedProcess.containsKey(fitUrl) -> RecordStatus.PROCESS_FAILED
            fitUrl in summary.uploaded -> RecordStatus.SYNCED
            else -> return null // 服务端无记录：保持现状，等下次同步自然重试
        }
        return target.takeIf { it != current }
    }

    fun sessionStatus(foundCount: Int, failedCount: Int): SessionStatus = when {
        foundCount == 0 -> SessionStatus.NO_NEW
        failedCount == 0 -> SessionStatus.SUCCESS
        else -> SessionStatus.PARTIAL
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SyncLogicTest"`
Expected: PASS，5 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncLogic.kt \
        android/app/src/test/java/com/dreammryang/onelaptogiant/sync/SyncLogicTest.kt
git commit -m "feat(android): reconcile 决策与会话状态归纳纯函数

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: SyncEngine 主流程（会话/去重/reconcile/下载/整批上传/进度/Mutex）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncEngine.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/sync/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `OnelapClient`（Task 6）、`GiantClient`/`AllUploadSummary`（Task 7）、`TokenManager`/`AuthFailedException`（Task 5）、DAO 与实体（Task 3）、`SyncLogic`（Task 8）
- Produces:
  - `enum class SyncStep(val label: String) { PREPARING("查询捷安特已上传列表"), RECONCILING("校正本地记录"), LISTING("查询顽鹿活动"), DOWNLOADING("下载 FIT 文件"), UPLOADING("上传到捷安特") }`
  - `data class SyncProgress(val step: SyncStep, val done: Int = 0, val total: Int = 0)`
  - `sealed interface SyncOutcome { data class Finished(val sessionId: Long, val status: SessionStatus) : SyncOutcome; data object Skipped : SyncOutcome }`
  - `class SyncEngine(onelap: OnelapClient, giant: GiantClient, onelapTokens: TokenManager, giantTokens: TokenManager, sessionDao: SyncSessionDao, recordDao: SyncRecordDao, recentDaysProvider: suspend () -> Int, fitDir: File, clock: () -> Long = { System.currentTimeMillis() })`:
    - `val progress: StateFlow<SyncProgress?>`（null=空闲）
    - `suspend fun sync(trigger: TriggerType): SyncOutcome`（tryLock 失败 → `Skipped`，不排队）
    - `suspend fun retryRecord(recordId: Long): SyncOutcome`（Task 10 实现，本任务先不建）

- [ ] **Step 1: 写失败测试**

`SyncEngineTest.kt`（Robolectric + in-memory Room + fake 客户端）：

```kotlin
package com.dreammryang.onelaptogiant.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreammryang.onelaptogiant.data.auth.Platform
import com.dreammryang.onelaptogiant.data.auth.TokenManager
import com.dreammryang.onelaptogiant.data.auth.TokenStore
import com.dreammryang.onelaptogiant.data.db.AppDatabase
import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncRecordEntity
import com.dreammryang.onelaptogiant.data.db.TriggerType
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary
import com.dreammryang.onelaptogiant.data.network.giant.GiantClient
import com.dreammryang.onelaptogiant.data.network.onelap.OnelapClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

// ---- fakes ----

private class FakeOnelap : OnelapClient {
    /** activityId -> fitUrl（null=该活动无 FIT） */
    var activities: List<Pair<String, String?>> = emptyList()
    var failDownload: Set<String> = emptySet()
    var downloadCount = 0

    override suspend fun listActivityIds(token: String, startDate: String, endDate: String) =
        activities.map { it.first }

    override suspend fun fetchFitUrl(token: String, activityId: String) =
        activities.first { it.first == activityId }.second

    override suspend fun downloadFit(token: String, fitUrl: String, targetDir: File): File {
        if (fitUrl in failDownload) throw IOException("模拟下载失败")
        downloadCount++
        targetDir.mkdirs()
        return File(targetDir, fitUrl).apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }
}

private class FakeGiant : GiantClient {
    var summary = AllUploadSummary(emptySet(), emptyMap())
    var allUploadError: Exception? = null
    var uploadOk = true
    val uploadedBatches = mutableListOf<List<String>>()
    var entered = false
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun fetchAllUpload(token: String): AllUploadSummary {
        entered = true
        gate?.await()
        allUploadError?.let { throw it }
        return summary
    }

    override suspend fun uploadFits(token: String, files: List<File>): Boolean {
        uploadedBatches += files.map { it.name }
        return uploadOk
    }
}

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var onelap: FakeOnelap
    private lateinit var giant: FakeGiant
    private lateinit var engine: SyncEngine
    private lateinit var fitDir: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("test_engine", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val tokenStore = TokenStore(prefs)
        onelap = FakeOnelap()
        giant = FakeGiant()
        fitDir = tmp.newFolder("fit")
        engine = SyncEngine(
            onelap = onelap,
            giant = giant,
            onelapTokens = TokenManager(Platform.ONELAP, tokenStore) { "ot" },
            giantTokens = TokenManager(Platform.GIANT, tokenStore) { "gt" },
            sessionDao = db.sessionDao(),
            recordDao = db.recordDao(),
            recentDaysProvider = { 30 },
            fitDir = fitDir,
            clock = { 1751443200000L },
        )
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `新文件下载上传成功会话 SUCCESS`() = runBlocking {
        onelap.activities = listOf("101" to "a.fit", "102" to "b.fit", "103" to null)

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.SUCCESS, outcome.status)
        val session = db.sessionDao().getById(outcome.sessionId)!!
        assertEquals(2, session.foundCount)
        assertEquals(2, session.downloadedCount)
        assertEquals(2, session.syncedCount)
        assertEquals(0, session.failedCount)
        assertNotNull(session.finishedAt)
        assertEquals(listOf(listOf("a.fit", "b.fit")), giant.uploadedBatches)
        assertEquals(RecordStatus.SYNCED, db.recordDao().getByFitUrl("a.fit")!!.status)
        assertTrue(File(fitDir, "a.fit").exists()) // 上传后文件保留
    }

    @Test
    fun `服务端已有的文件跳过且会话 NO_NEW`() = runBlocking {
        onelap.activities = listOf("101" to "a.fit")
        giant.summary = AllUploadSummary(setOf("a.fit"), emptyMap())

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.NO_NEW, outcome.status)
        assertEquals(0, onelap.downloadCount)
        assertTrue(giant.uploadedBatches.isEmpty())
    }

    @Test
    fun `reconcile 将本地 SYNCED 校正为 PROCESS_FAILED 并带错误信息`() = runBlocking {
        val sid = db.sessionDao().insert(
            com.dreammryang.onelaptogiant.data.db.SyncSessionEntity(
                triggerType = TriggerType.AUTO, status = SessionStatus.SUCCESS, startedAt = 1L,
            )
        )
        db.recordDao().insert(
            SyncRecordEntity(
                fitUrl = "bad.fit", sessionId = sid, status = RecordStatus.SYNCED,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        giant.summary = AllUploadSummary(setOf("bad.fit"), mapOf("bad.fit" to "失败: 解析出错"))

        engine.sync(TriggerType.AUTO)

        val record = db.recordDao().getByFitUrl("bad.fit")!!
        assertEquals(RecordStatus.PROCESS_FAILED, record.status)
        assertEquals("失败: 解析出错", record.errorMsg)
    }

    @Test
    fun `单条下载失败不中断整体且会话 PARTIAL`() = runBlocking {
        onelap.activities = listOf("101" to "a.fit", "102" to "bad.fit")
        onelap.failDownload = setOf("bad.fit")

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.PARTIAL, outcome.status)
        assertEquals(RecordStatus.SYNCED, db.recordDao().getByFitUrl("a.fit")!!.status)
        val failed = db.recordDao().getByFitUrl("bad.fit")!!
        assertEquals(RecordStatus.DOWNLOAD_FAILED, failed.status)
        assertNotNull(failed.errorMsg)
        val session = db.sessionDao().getById(outcome.sessionId)!!
        assertEquals(2, session.foundCount)
        assertEquals(1, session.syncedCount)
        assertEquals(1, session.failedCount)
    }

    @Test
    fun `整批上传失败全部标 UPLOAD_FAILED`() = runBlocking {
        onelap.activities = listOf("101" to "a.fit")
        giant.uploadOk = false

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.PARTIAL, outcome.status)
        assertEquals(RecordStatus.UPLOAD_FAILED, db.recordDao().getByFitUrl("a.fit")!!.status)
    }

    @Test
    fun `all_upload 失败会话整体 FAILED 且记录 error_msg`() = runBlocking {
        giant.allUploadError = IOException("网络不可用")

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.FAILED, outcome.status)
        val session = db.sessionDao().getById(outcome.sessionId)!!
        assertEquals("网络不可用", session.errorMsg)
        assertNotNull(session.finishedAt)
    }

    @Test
    fun `本地已下载且文件仍在时复用不重新下载`() = runBlocking {
        val sid = db.sessionDao().insert(
            com.dreammryang.onelaptogiant.data.db.SyncSessionEntity(
                triggerType = TriggerType.AUTO, status = SessionStatus.PARTIAL, startedAt = 1L,
            )
        )
        db.recordDao().insert(
            SyncRecordEntity(
                fitUrl = "a.fit", sessionId = sid, status = RecordStatus.DOWNLOADED,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        File(fitDir, "a.fit").writeBytes(byteArrayOf(9))
        onelap.activities = listOf("101" to "a.fit")

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.SUCCESS, outcome.status)
        assertEquals(0, onelap.downloadCount)
        assertEquals(RecordStatus.SYNCED, db.recordDao().getByFitUrl("a.fit")!!.status)
        assertEquals(outcome.sessionId, db.recordDao().getByFitUrl("a.fit")!!.sessionId)
    }

    @Test
    fun `同步进行中再次触发返回 Skipped`() = runBlocking {
        giant.gate = CompletableDeferred()
        val job = launch(Dispatchers.Default) { engine.sync(TriggerType.AUTO) }
        withTimeout(5000) { while (!giant.entered) delay(10) }

        assertEquals(SyncOutcome.Skipped, engine.sync(TriggerType.MANUAL))

        giant.gate!!.complete(Unit)
        job.join()
        assertFalse(engine.progress.value != null) // 结束后进度复位
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SyncEngineTest"`
Expected: FAIL，编译错误（SyncEngine 未定义）。

- [ ] **Step 3: 实现**

`SyncEngine.kt`：

```kotlin
package com.dreammryang.onelaptogiant.sync

import com.dreammryang.onelaptogiant.data.auth.TokenManager
import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncRecordDao
import com.dreammryang.onelaptogiant.data.db.SyncRecordEntity
import com.dreammryang.onelaptogiant.data.db.SyncSessionDao
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.data.db.TriggerType
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary
import com.dreammryang.onelaptogiant.data.network.giant.GiantClient
import com.dreammryang.onelaptogiant.data.network.onelap.OnelapClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class SyncStep(val label: String) {
    PREPARING("查询捷安特已上传列表"),
    RECONCILING("校正本地记录"),
    LISTING("查询顽鹿活动"),
    DOWNLOADING("下载 FIT 文件"),
    UPLOADING("上传到捷安特"),
}

data class SyncProgress(val step: SyncStep, val done: Int = 0, val total: Int = 0)

sealed interface SyncOutcome {
    data class Finished(val sessionId: Long, val status: SessionStatus) : SyncOutcome
    data object Skipped : SyncOutcome
}

class SyncEngine(
    private val onelap: OnelapClient,
    private val giant: GiantClient,
    private val onelapTokens: TokenManager,
    private val giantTokens: TokenManager,
    private val sessionDao: SyncSessionDao,
    private val recordDao: SyncRecordDao,
    private val recentDaysProvider: suspend () -> Int,
    private val fitDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress

    /** 一次同步 = 一个会话；已有同步在跑时直接跳过不排队 */
    suspend fun sync(trigger: TriggerType): SyncOutcome {
        if (!mutex.tryLock()) {
            Timber.i("已有同步在运行，跳过本次 %s 触发", trigger)
            return SyncOutcome.Skipped
        }
        try {
            return runSession(trigger)
        } finally {
            _progress.value = null
            mutex.unlock()
        }
    }

    private suspend fun runSession(trigger: TriggerType): SyncOutcome.Finished {
        val sessionId = sessionDao.insert(
            SyncSessionEntity(triggerType = trigger, status = SessionStatus.RUNNING, startedAt = clock())
        )
        try {
            // 1. 捷安特侧准备：每会话仅一次 all_upload（唯一去重事实源）
            _progress.value = SyncProgress(SyncStep.PREPARING)
            val summary = giantTokens.withAuthRetry { giant.fetchAllUpload(it) }

            // 2. 本机记录 reconcile
            _progress.value = SyncProgress(SyncStep.RECONCILING)
            reconcileLocal(summary)

            // 3. 顽鹿侧：活动列表 + 逐条详情，按服务端集合去重
            _progress.value = SyncProgress(SyncStep.LISTING)
            val days = recentDaysProvider()
            val endDate = LocalDate.now()
            val startDate = endDate.minusDays(days.toLong())
            val activityIds = onelapTokens.withAuthRetry {
                onelap.listActivityIds(it, startDate.format(DATE_FMT), endDate.format(DATE_FMT))
            }
            val candidates = mutableListOf<Pair<String, String>>() // activityId to fitUrl
            for (id in activityIds) {
                val fitUrl = onelapTokens.withAuthRetry { onelap.fetchFitUrl(it, id) } ?: continue
                if (fitUrl in summary.uploaded) continue
                candidates += id to fitUrl
            }
            if (candidates.isEmpty()) {
                finishSession(sessionId, SessionStatus.NO_NEW, 0, 0, 0, 0)
                return SyncOutcome.Finished(sessionId, SessionStatus.NO_NEW)
            }

            // 4. 下载（单条失败不中断；本地已下载且文件仍在则复用）
            var downloadedCount = 0
            var failedCount = 0
            val toUpload = mutableListOf<Pair<SyncRecordEntity, File>>()
            candidates.forEachIndexed { index, (activityId, fitUrl) ->
                _progress.value = SyncProgress(SyncStep.DOWNLOADING, index, candidates.size)
                val existing = recordDao.getByFitUrl(fitUrl)
                try {
                    val localFile = File(fitDir, fitUrl)
                    val reuse = existing?.status == RecordStatus.DOWNLOADED && localFile.exists()
                    val file = if (reuse) {
                        localFile
                    } else {
                        onelapTokens.withAuthRetry { onelap.downloadFit(it, fitUrl, fitDir) }
                    }
                    val record = upsertRecord(
                        existing, fitUrl, activityId, sessionId, RecordStatus.DOWNLOADED,
                        fileSize = file.length(),
                        downloadTime = if (reuse) existing?.downloadTime else clock(),
                    )
                    toUpload += record to file
                    downloadedCount++
                } catch (e: com.dreammryang.onelaptogiant.data.auth.AuthFailedException) {
                    throw e // 续登后仍失败属流程级问题，交给外层
                } catch (e: Exception) {
                    Timber.w(e, "下载失败: %s", fitUrl)
                    upsertRecord(
                        existing, fitUrl, activityId, sessionId, RecordStatus.DOWNLOAD_FAILED,
                        errorMsg = e.message ?: e.javaClass.simpleName,
                    )
                    failedCount++
                }
            }

            // 5. 整批上传，按整批结果标记（真实处理结果由下次会话 reconcile 确认）
            var syncedCount = 0
            if (toUpload.isNotEmpty()) {
                _progress.value = SyncProgress(SyncStep.UPLOADING, 0, toUpload.size)
                val ok = try {
                    giantTokens.withAuthRetry { giant.uploadFits(it, toUpload.map { p -> p.second }) }
                } catch (e: com.dreammryang.onelaptogiant.data.auth.AuthFailedException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "整批上传异常")
                    false
                }
                val newStatus = if (ok) RecordStatus.SYNCED else RecordStatus.UPLOAD_FAILED
                val now = clock()
                toUpload.forEach { (record, _) ->
                    recordDao.update(
                        record.copy(
                            status = newStatus,
                            syncTime = if (ok) now else record.syncTime,
                            errorMsg = if (ok) null else "整批上传失败",
                            updatedAt = now,
                        )
                    )
                }
                if (ok) syncedCount = toUpload.size else failedCount += toUpload.size
            }

            val status = SyncLogic.sessionStatus(candidates.size, failedCount)
            finishSession(sessionId, status, candidates.size, downloadedCount, syncedCount, failedCount)
            return SyncOutcome.Finished(sessionId, status)
        } catch (e: Exception) {
            // 流程级失败：登录 / all_upload / 活动列表等
            Timber.e(e, "同步会话失败")
            val session = sessionDao.getById(sessionId)
            if (session != null) {
                sessionDao.update(
                    session.copy(
                        status = SessionStatus.FAILED,
                        finishedAt = clock(),
                        errorMsg = e.message ?: e.javaClass.simpleName,
                    )
                )
            }
            return SyncOutcome.Finished(sessionId, SessionStatus.FAILED)
        }
    }

    private suspend fun reconcileLocal(summary: AllUploadSummary) {
        for (record in recordDao.getReconcilable()) {
            val newStatus = SyncLogic.reconcile(record.status, record.fitUrl, summary) ?: continue
            val now = clock()
            recordDao.update(
                record.copy(
                    status = newStatus,
                    errorMsg = if (newStatus == RecordStatus.PROCESS_FAILED) {
                        summary.failedProcess[record.fitUrl]
                    } else {
                        null
                    },
                    syncTime = if (newStatus == RecordStatus.SYNCED && record.syncTime == null) now else record.syncTime,
                    updatedAt = now,
                )
            )
        }
    }

    private suspend fun upsertRecord(
        existing: SyncRecordEntity?,
        fitUrl: String,
        activityId: String?,
        sessionId: Long,
        status: RecordStatus,
        fileSize: Long? = null,
        errorMsg: String? = null,
        downloadTime: Long? = null,
    ): SyncRecordEntity {
        val now = clock()
        return if (existing == null) {
            val entity = SyncRecordEntity(
                fitUrl = fitUrl, activityId = activityId, sessionId = sessionId, status = status,
                fileSize = fileSize, errorMsg = errorMsg, downloadTime = downloadTime,
                createdAt = now, updatedAt = now,
            )
            entity.copy(id = recordDao.insert(entity))
        } else {
            val entity = existing.copy(
                activityId = activityId ?: existing.activityId,
                sessionId = sessionId,
                status = status,
                fileSize = fileSize ?: existing.fileSize,
                errorMsg = errorMsg,
                downloadTime = downloadTime ?: existing.downloadTime,
                updatedAt = now,
            )
            recordDao.update(entity)
            entity
        }
    }

    private suspend fun finishSession(
        sessionId: Long,
        status: SessionStatus,
        found: Int,
        downloaded: Int,
        synced: Int,
        failed: Int,
    ) {
        val session = sessionDao.getById(sessionId) ?: return
        sessionDao.update(
            session.copy(
                status = status,
                finishedAt = clock(),
                foundCount = found,
                downloadedCount = downloaded,
                syncedCount = synced,
                failedCount = failed,
            )
        )
    }

    private companion object {
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SyncEngineTest"`
Expected: PASS，8 个测试通过。

- [ ] **Step 5: 回归全部测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部通过。

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncEngine.kt \
        android/app/src/test/java/com/dreammryang/onelaptogiant/sync/SyncEngineTest.kt
git commit -m "feat(android): SyncEngine 核心同步流程（服务端去重/reconcile/整批上传/进度/防并发）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: 手动重试单条记录（SyncEngine.retryRecord）

**Files:**
- Modify: `android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncEngine.kt`（追加方法）
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/sync/SyncEngineRetryTest.kt`

**Interfaces:**
- Consumes: Task 9 的 SyncEngine 全部内部件
- Produces: `suspend fun retryRecord(recordId: Long): SyncOutcome`——仅接受 `DOWNLOAD_FAILED`/`UPLOAD_FAILED` 记录；创建 MANUAL 会话执行剩余步骤；成功后记录归入新会话（原会话统计不变）；`PROCESS_FAILED` 不可重试（UI 层不给按钮，引擎侧 require 兜底）。

- [ ] **Step 1: 写失败测试**

`SyncEngineRetryTest.kt`（复用 Task 9 的 fake 思路；FakeOnelap/FakeGiant 在各自测试文件中为 private，这里重新声明本文件私有版本）：

```kotlin
package com.dreammryang.onelaptogiant.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreammryang.onelaptogiant.data.auth.Platform
import com.dreammryang.onelaptogiant.data.auth.TokenManager
import com.dreammryang.onelaptogiant.data.auth.TokenStore
import com.dreammryang.onelaptogiant.data.db.AppDatabase
import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncRecordEntity
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.data.db.TriggerType
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary
import com.dreammryang.onelaptogiant.data.network.giant.GiantClient
import com.dreammryang.onelaptogiant.data.network.onelap.OnelapClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

private class RetryFakeOnelap : OnelapClient {
    var failDownload = false
    var downloadCount = 0
    override suspend fun listActivityIds(token: String, startDate: String, endDate: String) = emptyList<String>()
    override suspend fun fetchFitUrl(token: String, activityId: String): String? = null
    override suspend fun downloadFit(token: String, fitUrl: String, targetDir: File): File {
        if (failDownload) throw IOException("模拟下载失败")
        downloadCount++
        targetDir.mkdirs()
        return File(targetDir, fitUrl).apply { writeBytes(byteArrayOf(1)) }
    }
}

private class RetryFakeGiant : GiantClient {
    var uploadOk = true
    val uploadedBatches = mutableListOf<List<String>>()
    override suspend fun fetchAllUpload(token: String) = AllUploadSummary(emptySet(), emptyMap())
    override suspend fun uploadFits(token: String, files: List<File>): Boolean {
        uploadedBatches += files.map { it.name }
        return uploadOk
    }
}

@RunWith(RobolectricTestRunner::class)
class SyncEngineRetryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var onelap: RetryFakeOnelap
    private lateinit var giant: RetryFakeGiant
    private lateinit var engine: SyncEngine
    private lateinit var fitDir: File
    private var originSessionId = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("test_retry", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val tokenStore = TokenStore(prefs)
        onelap = RetryFakeOnelap()
        giant = RetryFakeGiant()
        fitDir = tmp.newFolder("fit")
        engine = SyncEngine(
            onelap, giant,
            TokenManager(Platform.ONELAP, tokenStore) { "ot" },
            TokenManager(Platform.GIANT, tokenStore) { "gt" },
            db.sessionDao(), db.recordDao(),
            recentDaysProvider = { 30 },
            fitDir = fitDir,
            clock = { 42L },
        )
        originSessionId = runBlocking {
            db.sessionDao().insert(
                SyncSessionEntity(
                    triggerType = TriggerType.AUTO, status = SessionStatus.PARTIAL,
                    startedAt = 1L, foundCount = 5, failedCount = 2,
                )
            )
        }
    }

    @After
    fun teardown() = db.close()

    private fun insertRecord(status: RecordStatus, fitUrl: String = "r.fit"): Long = runBlocking {
        db.recordDao().insert(
            SyncRecordEntity(
                fitUrl = fitUrl, sessionId = originSessionId, status = status,
                errorMsg = "原错误", createdAt = 1L, updatedAt = 1L,
            )
        )
    }

    @Test
    fun `重试 UPLOAD_FAILED 且文件仍在时只上传不下载`() = runBlocking {
        val recordId = insertRecord(RecordStatus.UPLOAD_FAILED)
        File(fitDir, "r.fit").writeBytes(byteArrayOf(9))

        val outcome = engine.retryRecord(recordId) as SyncOutcome.Finished

        assertEquals(SessionStatus.SUCCESS, outcome.status)
        assertEquals(0, onelap.downloadCount)
        assertEquals(listOf(listOf("r.fit")), giant.uploadedBatches)
        val record = db.recordDao().getById(recordId)!!
        assertEquals(RecordStatus.SYNCED, record.status)
        assertEquals(outcome.sessionId, record.sessionId) // 记录归入新会话
        // 新会话为 MANUAL 且原会话统计不变
        assertEquals(TriggerType.MANUAL, db.sessionDao().getById(outcome.sessionId)!!.triggerType)
        val origin = db.sessionDao().getById(originSessionId)!!
        assertEquals(5, origin.foundCount)
        assertEquals(2, origin.failedCount)
    }

    @Test
    fun `重试 DOWNLOAD_FAILED 时先下载再上传`() = runBlocking {
        val recordId = insertRecord(RecordStatus.DOWNLOAD_FAILED)

        val outcome = engine.retryRecord(recordId) as SyncOutcome.Finished

        assertEquals(SessionStatus.SUCCESS, outcome.status)
        assertEquals(1, onelap.downloadCount)
        assertEquals(RecordStatus.SYNCED, db.recordDao().getById(recordId)!!.status)
        val session = db.sessionDao().getById(outcome.sessionId)!!
        assertEquals(1, session.foundCount)
        assertEquals(1, session.downloadedCount)
        assertEquals(1, session.syncedCount)
    }

    @Test
    fun `重试中上传失败记录回到 UPLOAD_FAILED 会话 PARTIAL`() = runBlocking {
        val recordId = insertRecord(RecordStatus.DOWNLOAD_FAILED)
        giant.uploadOk = false

        val outcome = engine.retryRecord(recordId) as SyncOutcome.Finished

        assertEquals(SessionStatus.PARTIAL, outcome.status)
        assertEquals(RecordStatus.UPLOAD_FAILED, db.recordDao().getById(recordId)!!.status)
    }

    @Test
    fun `重试中下载异常会话 FAILED 记录保持原状态`() = runBlocking {
        val recordId = insertRecord(RecordStatus.DOWNLOAD_FAILED)
        onelap.failDownload = true

        val outcome = engine.retryRecord(recordId) as SyncOutcome.Finished

        assertEquals(SessionStatus.FAILED, outcome.status)
        assertEquals(RecordStatus.DOWNLOAD_FAILED, db.recordDao().getById(recordId)!!.status)
    }

    @Test
    fun `PROCESS_FAILED 记录不可重试`(): Unit = runBlocking {
        val recordId = insertRecord(RecordStatus.PROCESS_FAILED)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.retryRecord(recordId) }
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SyncEngineRetryTest"`
Expected: FAIL，编译错误 `Unresolved reference: retryRecord`。

- [ ] **Step 3: 实现（SyncEngine.kt 内追加方法，放在 `sync()` 之后）**

```kotlin
    /** 历史界面对单条失败记录发起的手动重试；PROCESS_FAILED 不可重试（重传大概率无效） */
    suspend fun retryRecord(recordId: Long): SyncOutcome {
        if (!mutex.tryLock()) {
            Timber.i("已有同步在运行，跳过本次重试")
            return SyncOutcome.Skipped
        }
        try {
            val record = requireNotNull(recordDao.getById(recordId)) { "记录不存在: $recordId" }
            check(record.status == RecordStatus.DOWNLOAD_FAILED || record.status == RecordStatus.UPLOAD_FAILED) {
                "仅下载失败/上传失败记录可重试，当前状态: ${record.status}"
            }
            val sessionId = sessionDao.insert(
                SyncSessionEntity(
                    triggerType = TriggerType.MANUAL,
                    status = SessionStatus.RUNNING,
                    startedAt = clock(),
                )
            )
            try {
                _progress.value = SyncProgress(SyncStep.DOWNLOADING, 0, 1)
                val localFile = File(fitDir, record.fitUrl)
                var downloadedCount = 0
                val file = if (record.status == RecordStatus.UPLOAD_FAILED && localFile.exists()) {
                    localFile
                } else {
                    onelapTokens.withAuthRetry { onelap.downloadFit(it, record.fitUrl, fitDir) }
                        .also { downloadedCount = 1 }
                }
                var current = record.copy(
                    status = RecordStatus.DOWNLOADED,
                    sessionId = sessionId,
                    fileSize = file.length(),
                    downloadTime = if (downloadedCount == 1) clock() else record.downloadTime,
                    errorMsg = null,
                    updatedAt = clock(),
                )
                recordDao.update(current)

                _progress.value = SyncProgress(SyncStep.UPLOADING, 0, 1)
                val ok = giantTokens.withAuthRetry { giant.uploadFits(it, listOf(file)) }
                current = current.copy(
                    status = if (ok) RecordStatus.SYNCED else RecordStatus.UPLOAD_FAILED,
                    syncTime = if (ok) clock() else current.syncTime,
                    errorMsg = if (ok) null else "上传失败",
                    updatedAt = clock(),
                )
                recordDao.update(current)

                val status = if (ok) SessionStatus.SUCCESS else SessionStatus.PARTIAL
                finishSession(sessionId, status, 1, downloadedCount, if (ok) 1 else 0, if (ok) 0 else 1)
                return SyncOutcome.Finished(sessionId, status)
            } catch (e: Exception) {
                Timber.e(e, "手动重试失败: recordId=%d", recordId)
                sessionDao.getById(sessionId)?.let {
                    sessionDao.update(
                        it.copy(
                            status = SessionStatus.FAILED,
                            finishedAt = clock(),
                            errorMsg = e.message ?: e.javaClass.simpleName,
                        )
                    )
                }
                return SyncOutcome.Finished(sessionId, SessionStatus.FAILED)
            }
        } finally {
            _progress.value = null
            mutex.unlock()
        }
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SyncEngineRetryTest" --tests "*.SyncEngineTest"`
Expected: PASS，13 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncEngine.kt \
        android/app/src/test/java/com/dreammryang/onelaptogiant/sync/SyncEngineRetryTest.kt
git commit -m "feat(android): 单条失败记录手动重试（MANUAL 会话，记录归入新会话）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: WorkManager 调度（SyncWorker + SyncScheduler + AppContainer 装配）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncWorker.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncScheduler.kt`
- Modify: `android/app/src/main/java/com/dreammryang/onelaptogiant/di/AppContainer.kt`（完整装配数据/网络/引擎链）
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/sync/SyncSchedulerTest.kt`

**Interfaces:**
- Consumes: `SyncEngine`/`TriggerType`（Task 9）、Task 3–7 全部构件
- Produces:
  - `class SyncWorker(context, params) : CoroutineWorker`——从 `(applicationContext as App).container.syncEngine` 取引擎执行；`companion object { const val KEY_TRIGGER = "trigger" }`
  - `class SyncScheduler(workManager: WorkManager)`: `fun schedulePeriodic(intervalHours: Int, wifiOnly: Boolean)`（唯一周期任务，UPDATE 策略）、`fun triggerManual()`；`companion object { const val PERIODIC_WORK = "sync_periodic"; const val MANUAL_WORK = "sync_manual" }`
  - `AppContainer` 公开属性：`database: AppDatabase`、`tokenStore`、`credentialStore`、`settingsRepository`、`onelapApi`、`giantApi`、`onelapTokens`、`giantTokens`、`syncEngine`、`syncScheduler`（全部 `by lazy`，Robolectric 下不触发即不初始化加密件）

**设计偏差说明（执行者须知）**：设计文档 §7 要求手动同步用 expedited OneTimeWork；expedited 在 API<31 需前台服务通知（通知权限 + channel），而手动同步必然发生在 App 前台、普通 OneTimeWork 即时性已足够，故先用普通 `OneTimeWorkRequest`。若真机联调发现明显延迟，再升级 expedited 并补通知（届时更新设计文档）。

- [ ] **Step 1: 写失败测试**

`SyncSchedulerTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(workManager)
    }

    @Test
    fun `重复注册周期任务仍只有一个（UPDATE 策略）`() {
        scheduler.schedulePeriodic(intervalHours = 6, wifiOnly = false)
        scheduler.schedulePeriodic(intervalHours = 12, wifiOnly = true)

        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.UNMETERED, infos[0].constraints.requiredNetworkType)
    }

    @Test
    fun `手动同步入队唯一一次性任务`() {
        scheduler.triggerManual()
        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.MANUAL_WORK).get()
        assertEquals(1, infos.size)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SyncSchedulerTest"`
Expected: FAIL，编译错误（SyncScheduler 未定义）。

- [ ] **Step 3: 实现**

`SyncWorker.kt`：

```kotlin
package com.dreammryang.onelaptogiant.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dreammryang.onelaptogiant.App
import com.dreammryang.onelaptogiant.data.db.TriggerType

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = if (inputData.getString(KEY_TRIGGER) == TriggerType.MANUAL.name) {
            TriggerType.MANUAL
        } else {
            TriggerType.AUTO
        }
        val engine = (applicationContext as App).container.syncEngine
        // 业务成败已在会话中记账；失败靠下个周期自然重试，Worker 本身不重试
        engine.sync(trigger)
        return Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
    }
}
```

`SyncScheduler.kt`：

```kotlin
package com.dreammryang.onelaptogiant.sync

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dreammryang.onelaptogiant.data.db.TriggerType
import java.util.concurrent.TimeUnit

class SyncScheduler(private val workManager: WorkManager) {

    fun schedulePeriodic(intervalHours: Int, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to TriggerType.AUTO.name))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun triggerManual() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to TriggerType.MANUAL.name))
            .build()
        workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val PERIODIC_WORK = "sync_periodic"
        const val MANUAL_WORK = "sync_manual"
    }
}
```

`di/AppContainer.kt`（整体替换为完整装配）：

```kotlin
package com.dreammryang.onelaptogiant.di

import android.app.Application
import android.content.SharedPreferences
import androidx.room.Room
import androidx.work.WorkManager
import com.dreammryang.onelaptogiant.data.auth.CredentialStore
import com.dreammryang.onelaptogiant.data.auth.Platform
import com.dreammryang.onelaptogiant.data.auth.TokenManager
import com.dreammryang.onelaptogiant.data.auth.TokenStore
import com.dreammryang.onelaptogiant.data.auth.createSecurePrefs
import com.dreammryang.onelaptogiant.data.db.AppDatabase
import com.dreammryang.onelaptogiant.data.network.HttpClientProvider
import com.dreammryang.onelaptogiant.data.network.giant.GiantApi
import com.dreammryang.onelaptogiant.data.network.onelap.OnelapApi
import com.dreammryang.onelaptogiant.data.settings.SettingsRepository
import com.dreammryang.onelaptogiant.data.settings.settingsDataStore
import com.dreammryang.onelaptogiant.sync.SyncEngine
import com.dreammryang.onelaptogiant.sync.SyncScheduler
import kotlinx.coroutines.flow.first
import java.io.File

class AppContainer(private val app: Application) {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(app, AppDatabase::class.java, "sync.db").build()
    }

    private val securePrefs: SharedPreferences by lazy { createSecurePrefs(app) }

    val tokenStore: TokenStore by lazy { TokenStore(securePrefs) }

    val credentialStore: CredentialStore by lazy { CredentialStore(securePrefs, tokenStore) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(app.settingsDataStore) }

    val onelapApi: OnelapApi by lazy { OnelapApi(HttpClientProvider.client, HttpClientProvider.json) }

    val giantApi: GiantApi by lazy { GiantApi(HttpClientProvider.client, HttpClientProvider.json) }

    val onelapTokens: TokenManager by lazy {
        TokenManager(Platform.ONELAP, tokenStore) {
            onelapApi.login(
                requireNotNull(credentialStore.onelapAccount) { "顽鹿账号未配置" },
                requireNotNull(credentialStore.onelapPassword) { "顽鹿密码未配置" },
            )
        }
    }

    val giantTokens: TokenManager by lazy {
        TokenManager(Platform.GIANT, tokenStore) {
            giantApi.login(
                requireNotNull(credentialStore.giantUsername) { "捷安特账号未配置" },
                requireNotNull(credentialStore.giantPassword) { "捷安特密码未配置" },
            )
        }
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            onelap = onelapApi,
            giant = giantApi,
            onelapTokens = onelapTokens,
            giantTokens = giantTokens,
            sessionDao = database.sessionDao(),
            recordDao = database.recordDao(),
            recentDaysProvider = { settingsRepository.recentDays.first() },
            fitDir = File(app.filesDir, "fit"),
        )
    }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(WorkManager.getInstance(app)) }
}
```

- [ ] **Step 4: 运行确认通过并回归**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS（含新增 2 个调度测试）。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncWorker.kt \
        android/app/src/main/java/com/dreammryang/onelaptogiant/sync/SyncScheduler.kt \
        android/app/src/main/java/com/dreammryang/onelaptogiant/di/AppContainer.kt \
        android/app/src/test/java/com/dreammryang/onelaptogiant/sync/SyncSchedulerTest.kt
git commit -m "feat(android): WorkManager 调度（唯一周期任务 + 手动触发）与依赖装配

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 12: 首页（HomeViewModel + HomeScreen + 通用展示工具）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/common/StatusText.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/common/Formats.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/home/HomeViewModel.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/home/HomeScreen.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `SyncProgress`（Task 9）、`SyncSessionEntity`/枚举（Task 3）
- Produces:
  - `data class HomeUiState(val configured: Boolean = false, val progress: SyncProgress? = null, val lastSession: SyncSessionEntity? = null, val processFailedCount: Int = 0)`，属性 `val syncing: Boolean get() = progress != null`
  - `class HomeViewModel(configured: Flow<Boolean>, progress: Flow<SyncProgress?>, lastSession: Flow<SyncSessionEntity?>, processFailedCount: Flow<Int>, onSyncRequested: () -> Unit) : ViewModel`——`val uiState: StateFlow<HomeUiState>`、`fun onSyncClick()`
  - `@Composable fun HomeScreen(viewModel: HomeViewModel, onGoSettings: () -> Unit)`
  - `fun SessionStatus.label(): String`、`fun RecordStatus.label(): String`、`@Composable fun RecordStatus.color(): Color`（PROCESS_FAILED/失败态 → `MaterialTheme.colorScheme.error`）
  - `fun formatTime(millis: Long): String`（`yyyy-MM-dd HH:mm`，系统时区）
  - `fun TriggerType.label(): String`（AUTO→"自动"，MANUAL→"手动"）

- [ ] **Step 1: 写失败测试**

`HomeViewModelTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.home

import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.data.db.TriggerType
import com.dreammryang.onelaptogiant.sync.SyncProgress
import com.dreammryang.onelaptogiant.sync.SyncStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `聚合四路输入为 UiState`() = runTest {
        val session = SyncSessionEntity(
            id = 1, triggerType = TriggerType.AUTO, status = SessionStatus.SUCCESS, startedAt = 1L,
        )
        var triggered = 0
        val vm = HomeViewModel(
            configured = flowOf(true),
            progress = flowOf(SyncProgress(SyncStep.DOWNLOADING, 1, 3)),
            lastSession = flowOf(session),
            processFailedCount = flowOf(2),
            onSyncRequested = { triggered++ },
        )

        val state = vm.uiState.first { it.configured }

        assertTrue(state.syncing)
        assertEquals(SyncStep.DOWNLOADING, state.progress!!.step)
        assertEquals(1L, state.lastSession!!.id)
        assertEquals(2, state.processFailedCount)

        vm.onSyncClick()
        assertEquals(1, triggered)
    }

    @Test
    fun `默认状态未配置且空闲`() = runTest {
        val vm = HomeViewModel(flowOf(false), flowOf(null), flowOf(null), flowOf(0)) {}
        val state = vm.uiState.first()
        assertFalse(state.configured)
        assertFalse(state.syncing)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.HomeViewModelTest"`
Expected: FAIL，编译错误（HomeViewModel 未定义）。

- [ ] **Step 3: 实现**

`ui/common/StatusText.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.TriggerType

fun SessionStatus.label(): String = when (this) {
    SessionStatus.RUNNING -> "进行中"
    SessionStatus.SUCCESS -> "成功"
    SessionStatus.PARTIAL -> "部分成功"
    SessionStatus.FAILED -> "失败"
    SessionStatus.NO_NEW -> "无新记录"
}

fun RecordStatus.label(): String = when (this) {
    RecordStatus.DOWNLOADED -> "已下载"
    RecordStatus.DOWNLOAD_FAILED -> "下载失败"
    RecordStatus.SYNCED -> "已同步"
    RecordStatus.UPLOAD_FAILED -> "上传失败"
    RecordStatus.PROCESS_FAILED -> "处理失败"
}

fun TriggerType.label(): String = when (this) {
    TriggerType.AUTO -> "自动"
    TriggerType.MANUAL -> "手动"
}

@Composable
fun RecordStatus.color(): Color = when (this) {
    RecordStatus.SYNCED -> MaterialTheme.colorScheme.primary
    RecordStatus.DOWNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
    RecordStatus.DOWNLOAD_FAILED,
    RecordStatus.UPLOAD_FAILED,
    RecordStatus.PROCESS_FAILED,
    -> MaterialTheme.colorScheme.error
}
```

`ui/common/Formats.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun formatTime(millis: Long): String = TIME_FMT.format(Instant.ofEpochMilli(millis))
```

`ui/home/HomeViewModel.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.sync.SyncProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val configured: Boolean = false,
    val progress: SyncProgress? = null,
    val lastSession: SyncSessionEntity? = null,
    val processFailedCount: Int = 0,
) {
    val syncing: Boolean get() = progress != null
}

class HomeViewModel(
    configured: Flow<Boolean>,
    progress: Flow<SyncProgress?>,
    lastSession: Flow<SyncSessionEntity?>,
    processFailedCount: Flow<Int>,
    private val onSyncRequested: () -> Unit,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(configured, progress, lastSession, processFailedCount) { c, p, s, f ->
            HomeUiState(configured = c, progress = p, lastSession = s, processFailedCount = f)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onSyncClick() = onSyncRequested()
}
```

`ui/home/HomeScreen.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.ui.common.formatTime
import com.dreammryang.onelaptogiant.ui.common.label

@Composable
fun HomeScreen(viewModel: HomeViewModel, onGoSettings: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.configured) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("尚未配置账号", style = MaterialTheme.typography.titleMedium)
                    Text("请先在设置中填写顽鹿与捷安特的账号密码", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onGoSettings) { Text("去设置") }
                }
            }
        }

        if (state.processFailedCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    "有 ${state.processFailedCount} 个文件已上传但服务端处理失败，请在历史中查看详情并人工处理",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        state.lastSession?.let { LastSessionCard(it) }

        state.progress?.let { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("同步中：${p.step.label}")
                        if (p.total > 0) Text("${p.done}/${p.total}")
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Button(
            onClick = viewModel::onSyncClick,
            enabled = state.configured && !state.syncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.syncing) "同步中…" else "立即同步")
        }
    }
}

@Composable
private fun LastSessionCard(session: SyncSessionEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("上次同步", style = MaterialTheme.typography.titleMedium)
                Text(session.status.label(), style = MaterialTheme.typography.titleMedium)
            }
            Text("时间：${formatTime(session.startedAt)}（${session.triggerType.label()}触发）")
            Text("发现 ${session.foundCount} · 下载 ${session.downloadedCount} · 同步 ${session.syncedCount} · 失败 ${session.failedCount}")
            session.errorMsg?.let {
                Text("错误：$it", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
```

- [ ] **Step 4: 运行确认通过 + 编译检查**

Run: `./gradlew :app:testDebugUnitTest --tests "*.HomeViewModelTest" && ./gradlew :app:assembleDebug`
Expected: 测试 PASS（2 个），构建成功。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/ui/ \
        android/app/src/test/java/com/dreammryang/onelaptogiant/ui/
git commit -m "feat(android): 首页（上次同步卡片/进度/立即同步/处理失败提醒/未配置引导）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 13: 同步历史（会话列表 + 会话详情 + 单条重试）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/history/HistoryViewModel.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/history/HistoryScreen.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/history/SessionDetailViewModel.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/history/SessionDetailScreen.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/ui/history/SessionDetailViewModelTest.kt`

**Interfaces:**
- Consumes: DAO Flow（Task 3）、`SyncOutcome`（Task 9）、`label()`/`color()`/`formatTime()`（Task 12）
- Produces:
  - `class HistoryViewModel(sessions: Flow<List<SyncSessionEntity>>) : ViewModel`——`val sessions: StateFlow<List<SyncSessionEntity>>`
  - `class SessionDetailViewModel(records: Flow<List<SyncRecordEntity>>, retry: suspend (Long) -> SyncOutcome) : ViewModel`——`val records: StateFlow<List<SyncRecordEntity>>`、`val message: SharedFlow<String>`（重试结果提示）、`fun onRetry(recordId: Long)`
  - `@Composable fun HistoryScreen(viewModel: HistoryViewModel, onOpenSession: (Long) -> Unit)`
  - `@Composable fun SessionDetailScreen(viewModel: SessionDetailViewModel)`

- [ ] **Step 1: 写失败测试**

`SessionDetailViewModelTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.history

import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncRecordEntity
import com.dreammryang.onelaptogiant.sync.SyncOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private val record = SyncRecordEntity(
        id = 7, fitUrl = "a.fit", sessionId = 1, status = RecordStatus.UPLOAD_FAILED,
        createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `暴露记录列表`() = runTest {
        val vm = SessionDetailViewModel(flowOf(listOf(record))) { SyncOutcome.Skipped }
        assertEquals(listOf(record), vm.records.first { it.isNotEmpty() })
    }

    @Test
    fun `重试成功与被跳过时发出对应提示`() = runTest {
        var retried = mutableListOf<Long>()
        val vm = SessionDetailViewModel(flowOf(emptyList())) { id ->
            retried += id
            if (retried.size == 1) SyncOutcome.Finished(9L, SessionStatus.SUCCESS) else SyncOutcome.Skipped
        }
        val messages = mutableListOf<String>()
        val job = launch { vm.message.collect { messages += it } }

        vm.onRetry(7L)
        vm.onRetry(7L)

        assertEquals(listOf(7L, 7L), retried)
        assertEquals(2, messages.size)
        assertEquals("重试成功", messages[0])
        assertEquals("已有同步在进行，稍后再试", messages[1])
        job.cancel()
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionDetailViewModelTest"`
Expected: FAIL，编译错误（SessionDetailViewModel 未定义）。

- [ ] **Step 3: 实现**

`HistoryViewModel.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(sessions: Flow<List<SyncSessionEntity>>) : ViewModel() {
    val sessions: StateFlow<List<SyncSessionEntity>> =
        sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

`SessionDetailViewModel.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncRecordEntity
import com.dreammryang.onelaptogiant.sync.SyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionDetailViewModel(
    records: Flow<List<SyncRecordEntity>>,
    private val retry: suspend (recordId: Long) -> SyncOutcome,
) : ViewModel() {

    val records: StateFlow<List<SyncRecordEntity>> =
        records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message

    fun onRetry(recordId: Long) {
        viewModelScope.launch {
            val text = when (val outcome = retry(recordId)) {
                is SyncOutcome.Finished ->
                    if (outcome.status == SessionStatus.SUCCESS) "重试成功" else "重试未成功，详见历史"
                SyncOutcome.Skipped -> "已有同步在进行，稍后再试"
            }
            _message.emit(text)
        }
    }
}
```

`HistoryScreen.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreammryang.onelaptogiant.ui.common.formatTime
import com.dreammryang.onelaptogiant.ui.common.label

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onOpenSession: (Long) -> Unit) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    if (sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有同步记录", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSession(session.id) },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatTime(session.startedAt), style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${session.triggerType.label()} · ${session.status.label()}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(
                        "发现 ${session.foundCount} · 下载 ${session.downloadedCount} · " +
                            "同步 ${session.syncedCount} · 失败 ${session.failedCount}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    session.errorMsg?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
```

`SessionDetailScreen.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.ui.common.color
import com.dreammryang.onelaptogiant.ui.common.label

@Composable
fun SessionDetailScreen(viewModel: SessionDetailViewModel) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("该会话没有记录明细")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(records, key = { it.id }) { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(record.fitUrl, style = MaterialTheme.typography.bodyMedium)
                        record.activityId?.let {
                            Text("活动 $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(record.status.label(), color = record.status.color())
                            if (record.status == RecordStatus.DOWNLOAD_FAILED ||
                                record.status == RecordStatus.UPLOAD_FAILED
                            ) {
                                TextButton(onClick = { viewModel.onRetry(record.id) }) { Text("重试") }
                            }
                        }
                        record.errorMsg?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: 运行确认通过 + 编译检查**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SessionDetailViewModelTest" && ./gradlew :app:assembleDebug`
Expected: 测试 PASS（2 个），构建成功。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/ui/history/ \
        android/app/src/test/java/com/dreammryang/onelaptogiant/ui/history/
git commit -m "feat(android): 同步历史（会话列表/记录明细/失败重试/处理失败标红）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 14: 设置页（账号/天数/间隔/网络约束 + 保存后重排调度）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/settings/SettingsViewModel.kt`
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/settings/SettingsScreen.kt`
- Test: `android/app/src/test/java/com/dreammryang/onelaptogiant/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository`/`INTERVAL_OPTIONS`（Task 4）、`CredentialStore`（Task 4）
- Produces:
  - `data class SettingsUiState(val onelapAccount: String = "", val onelapPassword: String = "", val giantUsername: String = "", val giantPassword: String = "", val recentDays: String = "30", val intervalHours: Int = 6, val wifiOnly: Boolean = false, val loaded: Boolean = false)`
  - `class SettingsViewModel(settings: SettingsRepository, credentials: CredentialStore, schedule: (intervalHours: Int, wifiOnly: Boolean) -> Unit) : ViewModel`——`val uiState: StateFlow<SettingsUiState>`、`fun update(transform: (SettingsUiState) -> SettingsUiState)`、`fun save(): Job`（返回 Job 供测试 join 等待；UI 侧经 Unit 强转直接当 onClick 用）、`val saved: SharedFlow<Unit>`
  - `@Composable fun SettingsScreen(viewModel: SettingsViewModel)`
  - 保存语义：写入凭证（CredentialStore 内部自动清对应 token）与设置；四项账号齐全时调用 `schedule(...)`（覆盖「首次配置自动注册」与「改间隔/网络约束即时生效」两个需求）

- [ ] **Step 1: 写失败测试**

`SettingsViewModelTest.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.dreammryang.onelaptogiant.data.auth.CredentialStore
import com.dreammryang.onelaptogiant.data.auth.Platform
import com.dreammryang.onelaptogiant.data.auth.TokenStore
import com.dreammryang.onelaptogiant.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private lateinit var tokenStore: TokenStore
    private lateinit var credentials: CredentialStore
    private val scheduled = mutableListOf<Pair<Int, Boolean>>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = ioScope) {
            tmp.newFile("settings_vm.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_settings_vm", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenStore = TokenStore(prefs)
        credentials = CredentialStore(prefs, tokenStore)
        scheduled.clear()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        ioScope.cancel()
    }

    private fun vm() = SettingsViewModel(settings, credentials) { h, w -> scheduled += h to w }

    @Test
    fun `初始状态从存储加载`() = runTest {
        credentials.saveOnelap("acc", "pwd")
        settings.setIntervalHours(12)

        val state = vm().uiState.first { it.loaded }

        assertEquals("acc", state.onelapAccount)
        assertEquals(12, state.intervalHours)
        assertEquals("30", state.recentDays)
    }

    @Test
    fun `保存写入存储并清旧 token 且重排调度`() = runTest {
        tokenStore.set(Platform.ONELAP, "old-token")
        val vm = vm()
        vm.uiState.first { it.loaded }
        vm.update {
            it.copy(
                onelapAccount = "a", onelapPassword = "b",
                giantUsername = "c", giantPassword = "d",
                recentDays = "15", intervalHours = 3, wifiOnly = true,
            )
        }

        vm.save().join()

        assertEquals("a", credentials.onelapAccount)
        assertEquals("d", credentials.giantPassword)
        assertNull(tokenStore.get(Platform.ONELAP)) // 改账号清对应 token
        assertEquals(15, settings.recentDays.first())
        assertEquals(3, settings.intervalHours.first())
        assertTrue(settings.wifiOnly.first())
        assertEquals(listOf(3 to true), scheduled)
    }

    @Test
    fun `账号不全时保存不注册调度`() = runTest {
        val vm = vm()
        vm.uiState.first { it.loaded }
        vm.update { it.copy(onelapAccount = "a", onelapPassword = "b") } // 捷安特留空

        vm.save().join()

        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `非法天数回落默认 30`() = runTest {
        val vm = vm()
        vm.uiState.first { it.loaded }
        vm.update {
            it.copy(
                onelapAccount = "a", onelapPassword = "b",
                giantUsername = "c", giantPassword = "d", recentDays = "abc",
            )
        }

        vm.save().join()

        assertEquals(30, settings.recentDays.first())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SettingsViewModelTest"`
Expected: FAIL，编译错误（SettingsViewModel 未定义）。

- [ ] **Step 3: 实现**

`SettingsViewModel.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.auth.CredentialStore
import com.dreammryang.onelaptogiant.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val onelapAccount: String = "",
    val onelapPassword: String = "",
    val giantUsername: String = "",
    val giantPassword: String = "",
    val recentDays: String = "30",
    val intervalHours: Int = 6,
    val wifiOnly: Boolean = false,
    val loaded: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
    private val schedule: (intervalHours: Int, wifiOnly: Boolean) -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private val _saved = MutableSharedFlow<Unit>()
    val saved: SharedFlow<Unit> = _saved

    init {
        viewModelScope.launch {
            _uiState.value = SettingsUiState(
                onelapAccount = credentials.onelapAccount.orEmpty(),
                onelapPassword = credentials.onelapPassword.orEmpty(),
                giantUsername = credentials.giantUsername.orEmpty(),
                giantPassword = credentials.giantPassword.orEmpty(),
                recentDays = settings.recentDays.first().toString(),
                intervalHours = settings.intervalHours.first(),
                wifiOnly = settings.wifiOnly.first(),
                loaded = true,
            )
        }
    }

    fun update(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.value = transform(_uiState.value)
    }

    fun save(): Job =
        viewModelScope.launch {
            val s = _uiState.value
            val days = s.recentDays.toIntOrNull()?.coerceIn(1, 365) ?: 30
            credentials.saveOnelap(s.onelapAccount.trim(), s.onelapPassword)
            credentials.saveGiant(s.giantUsername.trim(), s.giantPassword)
            settings.setRecentDays(days)
            settings.setIntervalHours(s.intervalHours)
            settings.setWifiOnly(s.wifiOnly)
            // 首次配置齐全自动注册；后续改间隔/网络约束即时生效（UPDATE 策略重排）
            if (credentials.isConfigured()) {
                schedule(s.intervalHours, s.wifiOnly)
            }
            _uiState.value = s.copy(recentDays = days.toString())
            _saved.emit(Unit)
        }
}
```

`SettingsScreen.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreammryang.onelaptogiant.data.settings.INTERVAL_OPTIONS

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.saved.collect { snackbarHostState.showSnackbar("已保存") }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("顽鹿账号", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.onelapAccount,
                onValueChange = { v -> viewModel.update { it.copy(onelapAccount = v) } },
                label = { Text("账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.onelapPassword,
                onValueChange = { v -> viewModel.update { it.copy(onelapPassword = v) } },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("捷安特账号", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.giantUsername,
                onValueChange = { v -> viewModel.update { it.copy(giantUsername = v) } },
                label = { Text("账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.giantPassword,
                onValueChange = { v -> viewModel.update { it.copy(giantPassword = v) } },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("同步选项", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.recentDays,
                onValueChange = { v -> viewModel.update { it.copy(recentDays = v) } },
                label = { Text("同步最近天数") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            IntervalDropdown(
                selected = state.intervalHours,
                onSelect = { v -> viewModel.update { it.copy(intervalHours = v) } },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("仅 Wi-Fi 下同步")
                Switch(
                    checked = state.wifiOnly,
                    onCheckedChange = { v -> viewModel.update { it.copy(wifiOnly = v) } },
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = state.loaded,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun IntervalDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "$selected 小时",
            onValueChange = {},
            readOnly = true,
            label = { Text("同步间隔") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        androidx.compose.material3.ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            INTERVAL_OPTIONS.forEach { hours ->
                DropdownMenuItem(
                    text = { Text("$hours 小时") },
                    onClick = {
                        onSelect(hours)
                        expanded = false
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 4: 运行确认通过 + 编译检查**

Run: `./gradlew :app:testDebugUnitTest --tests "*.SettingsViewModelTest" && ./gradlew :app:assembleDebug`
Expected: 测试 PASS（4 个），构建成功。（`menuAnchor()`/`ExposedDropdownMenu` 的 API 若因 M3 版本差异报错，按编译提示改用对应重载即可，语义不变。）

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/ui/settings/ \
        android/app/src/test/java/com/dreammryang/onelaptogiant/ui/settings/
git commit -m "feat(android): 设置页（账号加密存储/天数/间隔/网络约束，保存即重排调度）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 15: 导航装配（底部导航三屏 + ViewModel 工厂接线）

**Files:**
- Create: `android/app/src/main/java/com/dreammryang/onelaptogiant/ui/AppNav.kt`
- Modify: `android/app/src/main/java/com/dreammryang/onelaptogiant/MainActivity.kt`（替换占位内容）

**Interfaces:**
- Consumes: Task 11 的 `AppContainer` 全部公开属性；Task 12/13/14 的全部 Screen 与 ViewModel
- Produces: `@Composable fun AppNav(container: AppContainer)`；App 完整可运行

- [ ] **Step 1: 实现 AppNav**

`ui/AppNav.kt`：

```kotlin
package com.dreammryang.onelaptogiant.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dreammryang.onelaptogiant.di.AppContainer
import com.dreammryang.onelaptogiant.ui.history.HistoryScreen
import com.dreammryang.onelaptogiant.ui.history.HistoryViewModel
import com.dreammryang.onelaptogiant.ui.history.SessionDetailScreen
import com.dreammryang.onelaptogiant.ui.history.SessionDetailViewModel
import com.dreammryang.onelaptogiant.ui.home.HomeScreen
import com.dreammryang.onelaptogiant.ui.home.HomeViewModel
import com.dreammryang.onelaptogiant.ui.settings.SettingsScreen
import com.dreammryang.onelaptogiant.ui.settings.SettingsViewModel

private data class TopDest(val route: String, val label: String, val icon: ImageVector)

private val TOP_DESTS = listOf(
    TopDest("home", "首页", Icons.Filled.Home),
    TopDest("history", "历史", Icons.Filled.History),
    TopDest("settings", "设置", Icons.Filled.Settings),
)

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TOP_DESTS.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route ||
                            (dest.route == "history" && currentRoute?.startsWith("session/") == true),
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                val vm: HomeViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        HomeViewModel(
                            configured = container.credentialStore.configured,
                            progress = container.syncEngine.progress,
                            lastSession = container.database.sessionDao().observeLatestFinished(),
                            processFailedCount = container.database.recordDao().observeProcessFailedCount(),
                            onSyncRequested = { container.syncScheduler.triggerManual() },
                        )
                    }
                })
                HomeScreen(vm, onGoSettings = { navController.navigate("settings") })
            }
            composable("history") {
                val vm: HistoryViewModel = viewModel(factory = viewModelFactory {
                    initializer { HistoryViewModel(container.database.sessionDao().observeAll()) }
                })
                HistoryScreen(vm, onOpenSession = { id -> navController.navigate("session/$id") })
            }
            composable(
                route = "session/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) { entry ->
                val sessionId = entry.arguments!!.getLong("sessionId")
                val vm: SessionDetailViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        SessionDetailViewModel(
                            records = container.database.recordDao().observeBySession(sessionId),
                            retry = { recordId -> container.syncEngine.retryRecord(recordId) },
                        )
                    }
                })
                SessionDetailScreen(vm)
            }
            composable("settings") {
                val vm: SettingsViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            settings = container.settingsRepository,
                            credentials = container.credentialStore,
                            schedule = { hours, wifiOnly ->
                                container.syncScheduler.schedulePeriodic(hours, wifiOnly)
                            },
                        )
                    }
                })
                SettingsScreen(vm)
            }
        }
    }
}
```

- [ ] **Step 2: 替换 MainActivity**

`MainActivity.kt` 整体替换为：

```kotlin
package com.dreammryang.onelaptogiant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dreammryang.onelaptogiant.ui.AppNav
import com.dreammryang.onelaptogiant.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        setContent {
            AppTheme {
                AppNav(container)
            }
        }
    }
}
```

- [ ] **Step 3: 构建 + 全量回归**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: 构建成功，全部测试通过。

- [ ] **Step 4: 模拟器冒烟（无账号也可做的部分）**

在 Android Studio 启动模拟器运行 app，确认：三个底部 Tab 可切换；首页显示「尚未配置账号」引导卡片且「立即同步」置灰；设置页各控件可输入、点保存出现「已保存」提示；历史页显示「还没有同步记录」。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dreammryang/onelaptogiant/ui/AppNav.kt \
        android/app/src/main/java/com/dreammryang/onelaptogiant/MainActivity.kt
git commit -m "feat(android): 底部导航三屏装配，App 全链路可运行

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 16: 真机联调验证（人工 checklist，需用户配合提供账号）

**Files:**
- Modify（联调后按需）: `docs/api/onelap.md`、`docs/api/giant.md`（认证失效实际错误码等契约修正）
- Modify: `android/CLAUDE.md`（「当前状态」从"设计已完成"更新为"已实现，联调通过"；该文件不入库）

无自动化步骤，逐项人工验证（对应设计文档 §10「联调验证」）。**此任务需要用户在场**：提供真实账号并观察结果。

- [ ] **Step 1: 配置账号** —— 真机安装 debug 包，设置页填入顽鹿/捷安特真实账号并保存；预期出现「已保存」，首页引导卡片消失、「立即同步」可点。
- [ ] **Step 2: 手动同步全链路** —— 点「立即同步」；预期进度卡依次出现「查询捷安特已上传列表 → 查询顽鹿活动 → 下载 → 上传」，结束后首页出现结果卡片。
- [ ] **Step 3: 历史可见** —— 历史页出现该会话，点进详情能看到记录明细（文件名、活动 id、状态）。
- [ ] **Step 4: 多端去重验证（关键）** —— 前置：该捷安特账号此前已用桌面版同步过历史文件。本次同步这些文件应被跳过（会话 found 数不含它们，不重复上传）；可再手动触发一次，预期 NO_NEW。
- [ ] **Step 5: 定时任务** —— 保存设置后等待一个周期（或临时把间隔改为 1 小时/用 `adb shell dumpsys jobscheduler` 观察），确认 AUTO 触发的会话出现在历史中。
- [ ] **Step 6: 认证失效路径抽查** —— 改错密码保存（清 token）再改回正确密码，同步应自动重新登录成功；若服务端实际失效错误码与契约文档不符，**先更新 `docs/api/` 对应文档末节，再调整 `ensureAuthorized` 判定**。
- [ ] **Step 7: 处理失败标红（如可构造）** —— 若账号下存在服务端处理失败的历史文件（`all_upload` 中该文件名无任何成功记录），同步后首页应出现红色提醒、详情中该记录标红且无重试按钮。无法构造时跳过，留待自然发生时观察。
- [ ] **Step 8: 收尾** —— 按联调结果更新 `docs/api/`（若有契约修正）与 `android/CLAUDE.md` 当前状态；如有代码修正，与用户确认后提交 `fix(android): ...`。

---

## 计划自检记录（编写时已完成）

1. **规格覆盖**：设计文档 §4（Task 4/5/6/7）、§5 含手动重试与异常处理（Task 9/10）、§6（Task 3/8）、§7（Task 11/14）、§8 三屏（Task 12/13/14/15）、§9 配置映射（Task 2/4/11/14）、§10 测试策略（各任务测试 + Task 16 联调）——无缺口。
2. **明确不做**（YAGNI，执行者不要自行加回）：反向上传工具、密集轮询 cron、文件日志、桌面版数据迁移、并发窗口处理。
3. **有意偏差**（已在对应任务标注）：手动同步暂用普通 OneTimeWork 而非 expedited（Task 11，联调不达预期再升级）；`sessionStatus` 把「全部失败但流程完整」归为 PARTIAL（Task 8，设计文档未细分此情形）。
4. **类型一致性**：`AllUploadSummary(uploaded, failedProcess)`、`SyncOutcome.Finished(sessionId, status)`、DAO 方法名等已跨任务核对一致；若执行中发现签名冲突，以先完成任务产出的实际代码为准并回改后续任务引用。





