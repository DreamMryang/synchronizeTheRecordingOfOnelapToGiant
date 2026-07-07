# 桌面端跨端去重与 Token 缓存改造实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/design/multi-client-sync.md` 把桌面端去重从本地 SQLite 改为捷安特 `all_upload` 服务端判定，本地库降级为记账层，并引入 token 缓存（懒失效 + 自动续登）与失败自然重试。

**Architecture:** 把可测的纯逻辑抽成独立单元（`TokenCache`、`AllUploadSummary`、`SyncLogic`、DAO 升级），HTTP 编排保留在两个 Service 中变薄。`Main.TaskJob` 串联新流程：查 all_upload → reconcile 本地记录 → 按服务端集合去重下载 → 整批上传。

**Tech Stack:** Java 8 + Maven、fastjson2、Apache HttpClient 4、SQLite（xerial sqlite-jdbc，支持 `ON CONFLICT DO UPDATE`）、JUnit 4。

## Global Constraints

- Java 8 语法（无 var、无新 API）；
- 不新增第三方依赖（无 Mockito；HTTP 编排层不做单测，逻辑全部下沉到可测单元）；
- 接口行为以 `docs/api/giant.md`、`docs/api/onelap.md` 为准；
- 提交信息中文 `type(desktop): 描述` 格式；本仓库规定 commit 前须征得用户同意（执行时统一在末尾请示，分逻辑提交）；
- 与用户沟通用简体中文，日志文案中文。

---

### Task 1: AuthFailedException + TokenCache（utils）

**Files:**
- Create: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/utils/AuthFailedException.java`
- Create: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/utils/TokenCache.java`
- Test: `desktop/src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/utils/TokenCacheTest.java`

**Interfaces:**
- Produces: `class AuthFailedException extends RuntimeException`（构造 `(String message)`）
- Produces: `TokenCache(Supplier<String> loginFn)`；`<T> T withAuthRetry(TokenCache.TokenCall<T> call)`；函数式接口 `TokenCall<T> { T call(String token); }`
- 语义：首次调用才登录并缓存 token；`call` 抛 `AuthFailedException` → 清缓存重新登录重试一次，再失败则异常上抛；登录返回 null/空串抛 `RuntimeException`。

- [ ] **Step 1: 写失败测试**（登录只发生一次、失效后续登重试、二次失败上抛、登录空 token 报错）
- [ ] **Step 2: 运行确认编译失败**（`mvn test -Dtest=TokenCacheTest` → 类不存在）
- [ ] **Step 3: 最小实现**
- [ ] **Step 4: 测试全绿**

### Task 2: AllUploadSummary 解析（service）

**Files:**
- Create: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/service/AllUploadSummary.java`
- Test: `desktop/src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/service/AllUploadSummaryTest.java`

**Interfaces:**
- Produces: `static AllUploadSummary parse(String json)`；`Set<String> getUploaded()`；`Map<String,String> getFailedProcess()`
- 规则（与 Android `GiantApi.buildSummary` 一致）：`uploaded` = 出现过的全部 `file`（任意状态，空白 file 过滤）；同名多条任一 `status=="成功"` 即成功；全部非成功 → `failedProcess[file] = "最后一条的status: msg"`（空白部分省略）；根 `status != 1` → 抛 `AuthFailedException`（契约：响应异常视为 token 失效）。

- [ ] **Step 1: 写失败测试**（成功单条、同名多条任一成功、全部失败取末条文案、空白 file 过滤、data 空、status!=1 抛 AuthFailedException）
- [ ] **Step 2: 确认失败 → Step 3: 实现 → Step 4: 全绿**

### Task 3: SyncRecordDao 升级（upsert / PROCESS_FAILED / reconcile 查询）

**Files:**
- Modify: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDao.java`
- Test: `desktop/src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDaoTest.java`

**Interfaces:**
- Produces: 常量 `STATUS_PROCESS_FAILED = "PROCESS_FAILED"`；
- `insertDownloaded` → 改名 `upsertDownloaded(String fitUrl, String account, long fileSize)`：`INSERT ... ON CONFLICT(fit_url) DO UPDATE`（状态回 DOWNLOADED、error_msg 清空、刷新 file_size/download_time/updated_at）；
- `markDownloadFailed` → 改名 `upsertDownloadFailed(...)`：同为 upsert（只更新 status/error_msg/updated_at）；
- 新增 `Map<String,String> findReconcilable()`：`status IN (SYNCED, UPLOAD_FAILED)` 的 fit_url→status；
- 新增 `updateStatus(String fitUrl, String status, String errorMsg)`：置状态与 error_msg；首次变为 SYNCED 时补 sync_time；
- **删除 `findAllFitUrls()`**（去重职责移除，本地库降级为记账层）及其测试。

- [ ] **Step 1: 改写测试**（upsert 幂等与失败→成功状态翻转、findReconcilable 过滤、updateStatus 补 sync_time、原 5/6 号测试改用新方法名）
- [ ] **Step 2: 确认失败 → Step 3: 实现 → Step 4: 全绿**

### Task 4: SyncLogic（reconcile 纯逻辑 + 落库编排）

**Files:**
- Create: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/service/SyncLogic.java`
- Test: `desktop/src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/service/SyncLogicTest.java`

**Interfaces:**
- Produces: `static String reconcileTarget(String currentStatus, String fitUrl, AllUploadSummary summary)`（与 Android `SyncLogic.reconcile` 同规则：仅 SYNCED/UPLOAD_FAILED 参与；failedProcess 命中 → PROCESS_FAILED；uploaded 命中 → SYNCED；服务端无记录 → null 保持现状自然重试；与现状相同 → null）
- Produces: `static void reconcileLocal(AllUploadSummary summary)`：遍历 `findReconcilable()` 落库；转 PROCESS_FAILED 时 `log.error("【处理失败】…需人工处理")`（改造项④），其余转移 `log.info`；末尾汇总服务端处理失败总数 `log.warn`。

- [ ] **Step 1: 写失败测试**（纯逻辑 5 种转移 + reconcileLocal 用临时 SQLite 集成验证落库与 error_msg）
- [ ] **Step 2: 确认失败 → Step 3: 实现 → Step 4: 全绿**

### Task 5: HttpClientUtil 状态码检查（修复静默存错误页 + 401/403 语义）

**Files:**
- Modify: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/utils/HttpClientUtil.java`
- Test: `desktop/src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/utils/HttpClientUtilTest.java`（用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起本地服务）

**Interfaces:**
- `doGet`：401/403 → 抛 `AuthFailedException`；其他非 200 保持 RuntimeException；
- `downloadFile`：**新增状态码检查**（现状：不检查，token 失效时会把错误页字节存成 FIT 文件）——200 才落盘；401/403 → `AuthFailedException`；其他 → RuntimeException。

- [ ] **Step 1: 写失败测试**（本地 server 返回 200/401/500 三分支；401 时不产生文件）
- [ ] **Step 2: 确认失败 → Step 3: 实现 → Step 4: 全绿**

### Task 6: SyncConstants + GiantBikeService 重构

**Files:**
- Modify: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/utils/SyncConstants.java`（新增 `GIANT_ALL_UPLOAD_URL = "https://ridelife.giant.com.cn/index.php/api/all_upload"`）
- Modify: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/service/GiantBikeService.java`

**Interfaces:**
- Produces: `AllUploadSummary fetchAllUploadSummary()`：token 缓存 + `withAuthRetry`（`AllUploadSummary.parse` 对 status!=1 抛 AuthFailedException → 自动续登重试一次）；all_upload 响应可达数千条，日志只打长度不打全文；
- `syncFitFilesToGiantBike(ArrayList<String>)`：登录逻辑移入 `TokenCache`；上传 `status != 1` → 抛 AuthFailedException（契约：status 异常视为失效）→ 续登重试一次；最终失败捕获并 `markUploadFailed`，成功 `markSynced`（记账语义不变）。
- 无单测（HTTP 编排，逻辑已下沉至 Task 1/2）；以编译 + 全量测试 + 人工审查验收。

- [ ] **Step 1: 实现 → Step 2: `mvn test` 全绿（回归）**

### Task 7: OnelapService 重构（服务端集合去重 + token 缓存 + 本地文件复用）

**Files:**
- Modify: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/service/OnelapService.java`

**Interfaces:**
- 签名变更：`ArrayList<String> downloadTheOnelapFitFile(Set<String> uploadedOnServer)`；
- 登录抽为 `private static String login()` 注入 `TokenCache`；列表/详情/下载均走 `withAuthRetry`；列表响应无 `data` → AuthFailedException（契约）；
- 去重仅用 `uploadedOnServer.contains(fitUrl)`（本地库不再参与判断）；
- 复用：本地状态为 DOWNLOADED/UPLOAD_FAILED 且文件存在非空 → 跳过下载直接进上传清单；
- 记账改用 `upsertDownloaded` / `upsertDownloadFailed`（自然重试不再撞 UNIQUE 约束）。
- 无单测（同 Task 6 理由）。

- [ ] **Step 1: 实现 → Step 2: `mvn test` 全绿（回归）**

### Task 8: Main.TaskJob 编排 + 文档更新 + 打包验证

**Files:**
- Modify: `desktop/src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/Main.java`
- Modify: `docs/design/multi-client-sync.md`（§6 desktop → 已完成）
- Modify: `desktop/PROJECT_DOCUMENTATION.md`（流程/架构描述更新）
- Modify: `desktop/CLAUDE.md`（本地文件：移除「待改造」、更新架构描述）

**Interfaces:**
- TaskJob 新流程：`fetchAllUploadSummary()` → `SyncLogic.reconcileLocal(summary)` → `downloadTheOnelapFitFile(summary.getUploaded())` → 非空则 `syncFitFilesToGiantBike(...)`。

- [ ] **Step 1: 实现编排 → Step 2: `mvn clean package` 成功（fat-jar 可产出）→ Step 3: 更新三处文档**

## Self-Review 结论

- 设计文档 §6 desktop 四项（①all_upload 去重 ②token 缓存 ③自然重试/移除永久跳过 ④处理失败日志明确输出）分别由 Task 2+6+7 / 1+6+7 / 3+7 / 4 覆盖；另补两项衍生必需改动：DAO upsert（Task 3，自然重试的前置）与 downloadFile 状态码修复（Task 5，token 缓存后的正确性前置）。
- 类型一致性：`AllUploadSummary` 在 Task 2 定义、4/6/7/8 消费；`TokenCache.TokenCall` 在 1 定义、6/7 消费；DAO 新方法名 3 定义、4/7 消费。
