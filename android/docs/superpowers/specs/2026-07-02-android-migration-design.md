# 设计文档：顽鹿 → 捷安特骑行记录同步 Android App

> 日期：2026-07-02（同日修订：去重方案改为捷安特服务端事实源）
> 状态：已与用户确认的设计
> 前置文档：[迁移前分析](../../migration-analysis.md) ·
> [接口契约](../../../../docs/api/) ·
> [跨端去重设计](../../../../docs/design/multi-client-sync.md)

## 1. 背景与目标

将桌面版 Java 程序（本仓库 `desktop/`）迁移为 Android App：自动将顽鹿运动（Onelap）的骑行 FIT 文件同步到捷安特骑行（Giant Ride Life）。本项目为多端项目，**多端可同时运行**。

相对桌面版的核心变化：

1. **多端去重**：去重依据从本地数据库改为捷安特服务端 `all_upload` 接口（唯一事实源），详见[跨端去重设计](../../../../docs/design/multi-client-sync.md)；本地数据库降级为展示层（会话历史/排查）。
2. **登录态缓存**：桌面版每次任务都重新登录；Android 版缓存 token，仅在失效后自动重新登录。
3. **同步会话**：每次同步作为一个会话（session）记账，同步记录归属会话，可在界面查看历史。
4. **手动同步**：首页提供「立即同步」按钮，用于需要即时同步的场景。
5. **真实处理状态**：借助 `all_upload` 的处理状态识别「已上传但服务端处理失败」的文件，界面标红提示人工处理。
6. **失败自然重试**：下载/上传失败的文件因服务端无记录，下次同步自动重试（废弃桌面版「失败永久跳过」规则）；「处理失败」不自动重试。
7. **配置界面化**：账号密码等配置从「固化在 jar 内」改为设置界面 + 加密存储，运行时可改。

### 明确不做（YAGNI）

- 不迁移反向上传工具 `UploadToOnelapMain`（依赖手工抓网页 token/cookie，不适合手机端，桌面版仍可用）。
- 不复刻「早 8~10 点每 15 分钟」的密集轮询 cron（有手动同步兜底）。
- 不做文件日志（同步历史在数据库中可查，调试用 Timber/logcat）。
- 不做桌面版 SQLite 数据迁移（服务端去重后无此需要：首次同步查 `all_upload` 即知哪些已上传）。
- 不处理多端同时同步的并发窗口（概率极低且不影响正确性，见跨端设计 §5）。

## 2. 技术栈

| 项 | 选型 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 数据库 | Room（表结构见 §6） |
| 后台调度 | WorkManager |
| 网络 | OkHttp（multipart 原生支持） |
| JSON | kotlinx.serialization |
| 配置存储 | DataStore（普通配置）+ EncryptedSharedPreferences（账号密码、token） |
| 日志 | Timber |
| minSdk / targetSdk | 26（Android 8.0）/ 最新稳定版 |
| 测试 | JUnit + Robolectric（DAO）+ MockWebServer（网络/续登/去重） |

## 3. 总体架构

单模块 App（位于本仓库 `android/`，Android Studio 直接打开该目录），MVVM 分层：

```
ui/        Compose 界面（首页、历史、设置）+ ViewModel（StateFlow 暴露状态）
data/      Repository、Room（db/）、OkHttp 封装（network/）、DataStore 配置（settings/）
sync/      SyncEngine（核心同步流程，纯 Kotlin 逻辑）
           SyncWorker（WorkManager 接入层，定时与手动共用）
```

核心原则：

- **SyncEngine 与触发方式解耦**：定时任务和手动按钮都只是「以不同 trigger_type 调 SyncEngine」。
- **全局 Mutex 防并发**：同一时刻只允许一个同步流程运行（对应桌面版 `@DisallowConcurrentExecution`）。后到的触发直接跳过并记日志，不排队。
- **进程可被杀**：不做任何常驻状态，每次同步独立完成资源获取与释放。

## 4. 登录态管理（TokenManager）

每个平台（顽鹿、捷安特）各一个 TokenManager，token 持久化在 EncryptedSharedPreferences：

1. 业务请求前取缓存 token，**无缓存才登录**；
2. 请求返回认证失败时：**清缓存 → 重新登录 → 重试该请求一次**，仍失败才抛错；
3. 认证失败判定集中在一处函数（判定规则见[接口契约](../../../../docs/api/)各文档末节，具体错误码以实际联调抓包为准，便于调整）；
4. 账号密码在设置中被修改时，清除对应平台缓存 token。

顽鹿登录签名算法见[顽鹿接口契约 §1](../../../../docs/api/onelap.md)。签名密钥为固定值，直接进代码常量；MD5 用 `java.security.MessageDigest` 实现。

## 5. 同步流程（SyncEngine）

一次同步 = 一个会话，协程中执行：

1. 创建 `sync_session`（status=RUNNING，trigger_type=AUTO/MANUAL）；
2. **捷安特侧准备**：取捷安特 token（TokenManager）→ 调 `all_upload` 拉取全量已上传列表（**每会话仅此一次**）→ 内存构建：
   - `uploadedSet: HashSet<文件名>`（任意状态出现过即在集合中，去重用）；
   - `failedProcessSet: HashSet<文件名>`（有记录但无任何一条成功，标红用）；
3. **本机记录 reconcile**：用上述集合校正本地历史记录状态——本地 `SYNCED`/`UPLOAD_FAILED` 的记录若服务端已有成功记录 → `SYNCED`；若服务端有记录但全部非成功 → `PROCESS_FAILED`（标红）；
4. **顽鹿侧**：取顽鹿 token → 查最近 N 天活动列表（先 limit=20 取 total，再全量取回）→ 逐条查详情拿 `fitUrl`；
5. **去重**：`fitUrl ∈ uploadedSet` → 跳过（不下载不上传）；本地库不参与去重判定（若本地已有该 fitUrl 的 `DOWNLOADED` 记录且文件仍在，可直接复用文件跳过下载，属实现优化）；
6. **下载**：新文件下载到 `context.filesDir/fit/`，逐条写 `sync_record`（DOWNLOADED / DOWNLOAD_FAILED）；
7. **上传**：本批 DOWNLOADED 文件整批 multipart 上传 → 按整批结果标记 SYNCED / UPLOAD_FAILED（沿用桌面版整批语义；真实处理结果由下次会话的 reconcile 确认，因服务端处理疑似异步）；
8. 结束会话：写 finished_at、统计数与最终 status（见 §6）；
9. 过程中通过 Flow 向 UI 上报进度（当前步骤、已下载/已上传数）。

**手动重试**：历史界面对单条 `DOWNLOAD_FAILED`/`UPLOAD_FAILED` 记录发起 → 创建 MANUAL 会话 → 按记录状态执行剩余步骤 → 成功后更新该记录的 status 和 session_id（记录归入新会话，原会话统计不变）。`PROCESS_FAILED` 记录**不提供重试**（重传大概率无效），仅标红展示错误信息，人工处理。

**异常处理**：任一平台登录失败、`all_upload` 或活动列表查询失败 → 会话整体 FAILED 并记 error_msg；单条下载失败不中断整体流程。上传成功后 FIT 文件保留不删（与桌面版一致）。

## 6. 数据模型（Room，仅展示层，不承担去重）

```sql
-- 会话表
sync_session (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  trigger_type TEXT NOT NULL,      -- AUTO / MANUAL
  status       TEXT NOT NULL,      -- RUNNING / SUCCESS / PARTIAL / FAILED / NO_NEW
  started_at   INTEGER NOT NULL,   -- 毫秒时间戳（下同）
  finished_at  INTEGER,
  found_count      INTEGER NOT NULL DEFAULT 0,  -- 本次发现的新活动数
  downloaded_count INTEGER NOT NULL DEFAULT 0,
  synced_count     INTEGER NOT NULL DEFAULT 0,
  failed_count     INTEGER NOT NULL DEFAULT 0,
  error_msg    TEXT
)

-- 记录表
sync_record (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  fit_url        TEXT NOT NULL UNIQUE,   -- 本机维度唯一（仅约束本地不重复记账，不是多端去重键）
  activity_id    TEXT,                   -- 顽鹿活动 id，便于展示与排查
  session_id     INTEGER NOT NULL,       -- 最后处理该记录的会话
  status         TEXT NOT NULL,          -- 见下方状态机
  file_size      INTEGER,
  error_msg      TEXT,
  download_time  INTEGER,
  sync_time      INTEGER,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
)
```

**record 状态机**：`DOWNLOADED →（上传）→ SYNCED / UPLOAD_FAILED`；下载失败记 `DOWNLOAD_FAILED`；reconcile 可将 `SYNCED`/`UPLOAD_FAILED` 校正为 `SYNCED` 或 `PROCESS_FAILED`（服务端处理失败，界面标红，无重试）。

**session 状态**：全部成功 → SUCCESS；有成有败 → PARTIAL；流程级失败（登录/all_upload/列表失败）→ FAILED；无新记录 → NO_NEW。

## 7. 调度（WorkManager）

- 唯一周期任务（`enqueueUniquePeriodicWork`，UPDATE 策略），间隔可选 关闭/1/3/6/12/24 小时，默认 6 小时；选「关闭」即取消周期任务（`cancelUniqueWork`），停用自动同步，手动同步不受影响；
- 网络约束可选：任意网络（默认）/ 仅 Wi-Fi（`NetworkType.UNMETERED`）；
- 手动同步：`OneTimeWorkRequest`（expedited），与周期任务共用 SyncWorker；并发由 SyncEngine 的 Mutex 保证；
- 接受 WorkManager 非精确调度（Doze 延迟），不申请精确闹钟权限；
- 首次配置完成（账号齐全）后自动注册周期任务；设置中改间隔/网络约束即时生效（重新 enqueue）。

调度频率同时受 `all_upload` 效率约定约束（每会话一次全量拉取，见跨端设计 §4），当前默认频率下每天仅个位数次请求，无压力。

## 8. UI（Compose + Material 3，底部导航 2 屏）

1. **同步历史**：承载同步入口与状态展示——顶部横幅区依条件显示未配置引导卡（含「去设置」按钮）、`PROCESS_FAILED` 标红提醒卡、同步进行中的进度卡（步骤 + 计数）；下方会话列表（倒序：时间、触发方式、状态、统计，列表首项即上次同步结果）→ 点击进入会话详情，列出该会话的记录明细（活动 id、文件名、状态、错误信息），`DOWNLOAD_FAILED`/`UPLOAD_FAILED` 带「重试」按钮，`PROCESS_FAILED` 标红展示、无重试；底部冻结「立即同步」按钮（未配置或同步中禁用）；同步完成时 Snackbar 提示并可一键回到顶部。长按会话卡片可删除该会话及其本地记录；详情页每条记录可单独删除（删除 `PROCESS_FAILED` 记录即不再提醒）——本地库纯展示层，本地删除不影响服务端去重。
2. **设置**：顽鹿账号/密码、捷安特账号/密码（加密存储，修改后清对应 token 缓存）、同步天数（默认 30）、同步间隔（默认 6 小时，可选关闭以停用自动同步）、网络约束（默认任意网络）。账号密码、同步天数均在独立弹窗中编辑，确定即保存；同步间隔下拉与仅 Wi-Fi 开关选中/拨动即保存。设置项即时保存（输入类经弹窗确认），无页面级保存按钮；账号/间隔/网络约束保存后即触发调度重排（间隔选「关闭」则取消周期任务），天数不影响调度。数据管理：清空同步历史（删除全部本地会话与记录，含本地 FIT 缓存；不影响捷安特服务端数据与去重）。

## 9. 配置项映射

| 桌面版 config.properties | Android 去向 |
|---|---|
| onelap.sign.key | 代码常量 |
| onelap.account / onelap.password | 设置页 + EncryptedSharedPreferences |
| giant.username / giant.password | 设置页 + EncryptedSharedPreferences |
| sync.recent.days | 设置页（默认 30） |
| onelap.fit.file.storage.directory | 固定 `filesDir/fit/`，不暴露配置 |
| sync.db.path | Room 默认路径，不暴露配置 |
| sync.cronone/crontwo.expression | 设置页「同步间隔」（默认 6 小时） |
| log.file.path | 移除（无文件日志） |
| upload.toonelap.* | 移除（不迁移反向上传） |

## 10. 测试策略

- **单元测试**：顽鹿签名算法（与桌面版产出对拍）；`all_upload` 响应解析与集合构建（含同名多条记录：任意成功即成功）；会话状态归纳逻辑。
- **DAO 测试**（Robolectric）：会话/记录写入查询、状态流转、reconcile 更新。
- **网络测试**（MockWebServer）：token 缓存命中不登录、401 后自动续登并重试一次、二次失败抛错；服务端已有文件 → 跳过下载上传；服务端处理失败 → reconcile 为 PROCESS_FAILED；整批上传成败标记。
- **联调验证**：真机跑通「配置账号 → 手动同步 → 历史可见会话与记录 → 与桌面版已同步的历史文件不重复上传 → 定时任务触发」全链路。
