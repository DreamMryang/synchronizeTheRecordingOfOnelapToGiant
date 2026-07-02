# 迁移前分析：synchronizeTheRecordingOfOnelapToGiant → Android

> 分析日期：2026-07-02
> 源项目：本仓库 `desktop/`（Java 8 + Maven，fat-jar 常驻后台程序）
>
> ⚠️ 本文为迁移启动时的历史快照。其中「外部接口清单」已升级为权威契约文档（见根目录 `docs/api/`，含后来发现的捷安特 `all_upload` 接口）；「本地 SQLite 去重」设计已被跨端服务端去重方案取代（见 `docs/design/multi-client-sync.md`）。以最新文档为准。

## 一、源项目功能概述

将顽鹿运动（Onelap）的骑行 FIT 文件自动同步到捷安特骑行（Giant Ride Life）：

1. **定时调度**：Quartz 双 cron 触发器（每 6 小时一次 + 早 8~10 点每 15 分钟一次），启动时立即执行一次。任务体 `@DisallowConcurrentExecution` 防并发。
2. **下载（OnelapService）**：MD5 签名登录顽鹿（请求头 nonce/timestamp/sign，密钥固定值）→ 查最近 N 天活动列表（先查 total 再全量取回）→ 逐条查详情拿 `fitUrl` → 用 SQLite 已记录的全部 `fit_url` 去重 → 下载新 FIT 文件到本地目录。
3. **上传（GiantBikeService）**：表单登录捷安特拿 `user_token` → 本批文件整批 multipart 上传（`files[]` + token + device=bike_computer + brand=onelap）。
4. **同步记录（SyncRecordDao，SQLite）**：单表 `sync_record`，`fit_url` 唯一键即去重键。状态机 `DOWNLOADED → SYNCED / UPLOAD_FAILED`，下载失败记 `DOWNLOAD_FAILED`。**凡 fit_url 已有任意记录（含失败态）即永久跳过，失败不自动重试。** 捷安特上传按整批成功/失败标记。
5. **独立反向工具（UploadToOnelapMain）**：把本地目录的 FIT 文件逐个上传回顽鹿，token/cookie 需从网页手工抓取填配置，每次上传后 sleep 2 秒规避服务端文件名并发冲突。

## 二、源码清单与职责

| 文件 | 职责 |
|---|---|
| `Main.java` | 主入口：初始化 DB、注册 Quartz 双 cron、内部类 `TaskJob` 串联下载→上传 |
| `service/OnelapService.java` | 顽鹿登录（MD5 签名）、活动列表/详情查询、FIT 下载、写同步记录 |
| `service/GiantBikeService.java` | 捷安特登录、整批 multipart 上传、更新同步记录状态 |
| `db/SyncRecordDao.java` | 唯一数据访问层：静态单连接 + WAL + 全方法 synchronized |
| `utils/HttpClientUtil.java` | Apache HttpClient 连接池单例（10s 连接 / 30s socket 超时），doGet/doPost/downloadFile |
| `utils/ConfigManager.java` | classpath 加载 `config.properties`，缺 key 直接抛异常 |
| `utils/SyncConstants.java` | 全部外部接口地址与固定参数 |
| `UploadToOnelapMain.java` | 独立反向上传工具入口 |
| `src/test/.../SyncRecordDaoTest.java` | DAO 单元测试（JUnit 4） |

## 三、外部接口清单（全部 HTTPS，无明文流量问题）

**顽鹿运动：**

| 接口 | 方法 | 说明 |
|---|---|---|
| `https://www.onelap.cn/api/login` | POST JSON | 签名登录。请求头 `nonce`（UUID 去横线取后 16 位）、`timestamp`（秒）、`sign`；body `{account, password:md5(密码)}`；`sign = md5("account=..&nonce=..&password=..&timestamp=..&key=<onelap.sign.key>")`；响应 `data[0].token` |
| `https://u.onelap.cn/api/otm/ride_record/list` | POST JSON | 请求头 `Authorization: token`；body `{page, limit, start_date, end_date}`；先 limit=20 取 `data.pagination.total`，再以 total 为 limit 全量取 `data.list` |
| `https://u.onelap.cn/api/otm/ride_record/analysis/{id}` | GET | 详情，取 `data.ridingRecord.fitUrl` |
| `https://u.onelap.cn/api/otm/ride_record/analysis/fit_content/{base64(fitUrl)}` | GET | 下载 FIT 文件字节流 |
| `https://u.onelap.cn/upload/fit` | POST multipart | 反向上传：字段 `jilu`（文件）、`_token`；请求头 cookie（页面抓取） |

**捷安特骑行：**

| 接口 | 方法 | 说明 |
|---|---|---|
| `https://ridelife.giant.com.cn/index.php/api/login` | POST 表单 | `username`/`password`，响应 `user_token` |
| `https://ridelife.giant.com.cn/index.php/api/upload_fit` | POST multipart | `files[]`（可多文件）、`token`、`device=bike_computer`、`brand=onelap`；响应 `status==1` 为成功 |

## 四、数据模型（SQLite 表 `sync_record`）

```sql
CREATE TABLE IF NOT EXISTS sync_record (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  fit_url        TEXT    NOT NULL UNIQUE,   -- 去重键
  status         TEXT    NOT NULL,          -- DOWNLOADED / SYNCED / UPLOAD_FAILED / DOWNLOAD_FAILED
  onelap_account TEXT,
  file_size      INTEGER,
  error_msg      TEXT,
  download_time  INTEGER,                   -- 毫秒时间戳
  sync_time      INTEGER,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
)
```

## 五、配置项清单（`config.properties`）

| key | 说明 | Android 迁移去向建议 |
|---|---|---|
| `onelap.sign.key` | 顽鹿签名密钥（固定值） | 常量/BuildConfig |
| `onelap.account` / `onelap.password` | 顽鹿账号密码 | 设置界面 + 加密存储 |
| `giant.username` / `giant.password` | 捷安特账号密码 | 设置界面 + 加密存储 |
| `sync.recent.days` | 同步最近活动天数（默认 30） | 设置项，带默认值 |
| `onelap.fit.file.storage.directory` | FIT 文件存储目录 | 应用私有目录，无需配置 |
| `sync.db.path` | SQLite 库文件路径 | Room/内置 SQLite，无需配置 |
| `sync.cronone.expression` / `sync.crontwo.expression` | 双 cron 表达式 | 重新设计为 Android 调度策略（见风险点 1） |
| `log.file.path` | 日志目录 | Android 日志方案重新设计 |
| `upload.toonelap.*` | 反向上传工具配置 | 视是否保留该功能 |

## 六、依赖迁移映射

| 源依赖 | Android 可用性 | 替代方案 |
|---|---|---|
| Apache HttpClient (httpmime 4.5.14) | ❌ Android 6.0+ 已移除内置版，第三方版也不推荐 | **OkHttp**（或 Retrofit），multipart 原生支持 |
| sqlite-jdbc（xerial） | ❌ native 库不含 Android 平台 | **Room**（或 android.database.sqlite） |
| Quartz 2.3.2 | ❌ 不适用于 Android 进程模型 | **WorkManager**（必要时 AlarmManager 精确闹钟） |
| logback-classic 1.2.13 | ⚠️ 需换 logback-android，或直接换方案 | Timber / android.util.Log（+ 可选文件日志） |
| fastjson2 2.0.43 | ✅ 有 Android 支持 | 可保留；Kotlin 项目更常用 kotlinx.serialization / Moshi |
| commons-codec DigestUtils（MD5） | ⚠️ Android 自带旧版 commons-codec 易冲突 | `java.security.MessageDigest` 手写几行即可 |
| commons-lang3 / commons-collections4 | ✅ 可用 | Kotlin 标准库可完全替代，建议移除 |
| JUnit 4 | ✅ | 保留（或升 JUnit 5 / 加 androidTest） |

## 七、关键差异与风险点（迁移时必须重新设计的部分）

1. **调度模型完全不同**：Quartz cron「每 6 小时 + 早 8~10 点每 15 分钟」无法在 Android 上原样复刻。WorkManager 周期任务最小间隔 15 分钟且受 Doze/电池优化影响，不保证准点；「8~10 点每 15 分钟」这种窗口密集轮询需要额外设计（如周期任务内判断时间窗，或 `setExactAndAllowWhileIdle` 精确闹钟 + 特殊权限）。**需要和产品预期对齐：手机端到底需要多高的同步实时性。**
2. **进程不常驻**：桌面版是 JVM 常驻进程；Android 上应用随时可能被杀。`SyncRecordDao` 的「静态单连接常驻」模式失效，每次任务需独立获取/释放资源（Room 天然解决）。
3. **网络不能在主线程**：所有 HTTP 调用必须放协程/Worker 后台线程（源码是同步阻塞式调用，逻辑可保留但执行环境要换）。
4. **配置固化在 jar → 运行时可改**：桌面版改配置要重新打包；Android 版应做成设置界面（DataStore/SharedPreferences），账号密码用 EncryptedSharedPreferences 或 Keystore 加密。这是体验上的升级点。
5. **文件路径**：绝对路径（`/home/...`、`E:\...`）全部失效，改用 `context.getFilesDir()`/`getExternalFilesDir()` 应用私有目录，无需存储权限。
6. **失败不重试的业务规则**：桌面版靠人工看日志/改库处理失败记录；手机上没有这个途径，**建议 Android 版增加同步历史界面 + 失败记录手动重试入口**（属于功能增强，需确认）。
7. **无 UI → 需要 UI**：源项目是纯后台程序；Android 至少需要：账号配置页、同步开关/频率设置、手动触发同步按钮、（可选）同步历史列表。UI 范围需确认。
8. **反向上传工具（UploadToOnelapMain）**：依赖手工抓 token/cookie，交互模式不适合手机端，是否迁移需确认。
9. **网络环境**：手机可能在蜂窝网络下，是否限制「仅 Wi-Fi 同步」可作为 WorkManager 约束项（需确认）。

## 八、待用户决策的问题

1. App 形态：最小后台同步工具 vs 带同步历史/手动重试的完整 App？
2. 开发语言：Kotlin（推荐，配合 Compose/Room/WorkManager 生态）还是沿用 Java？
3. UI 方案：Jetpack Compose + Material Design 3（推荐）还是 XML View？
4. 调度策略：接受 WorkManager 的非精确周期（省电、免特殊权限）还是要求精确闹钟？
5. 是否迁移反向上传工具（UploadToOnelapMain）？
6. 同步网络约束：任意网络 / 仅 Wi-Fi / 可配置？
