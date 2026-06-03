# 引入 SQLite 维护同步记录 · 设计文档

- 日期：2026-06-03
- 范围：将"已同步 fit 文件记录"从平文本 txt 迁移到嵌入式 SQLite，并扩展为**完整生命周期**记录。

## 1. 背景与现状

当前同步记录机制：

- `OnelapService.downloadTheOnelapFitFile()` 把整份 txt（`sync.fit.file.save.path`）读进 `ArrayList`，用 `list.contains(fileKey)` 做去重，决定哪些活动需要下载。
- `GiantBikeService.syncFitFilesToGiantBike()` 在捷安特上传**整批**返回 `status==1` 时，用 `TxtOperationUtil.writeTxtFile` 把这一批 `fileKey` 前插到 txt。
- 记录中**只有 fileKey 一个字段**，无时间、状态、账号等元数据。

现状痛点：

- `writeTxtFile` 先读全文到 byte[]、`seek(0)` 再写，**中途崩溃会损坏/截断文件**（非原子）。
- 去重为 `O(n)` 线性扫描，记录无限增长。
- "已下载"与"已同步"被混为一谈，失败无任何痕迹，不可审计。
- 任一文件下载失败会抛异常中断整个 job。

## 2. 目标

- 用 SQLite 单表替代 txt，`file_key` 唯一索引做去重，写入具备事务原子性。
- 记录完整生命周期状态：已下载 / 已同步 / 上传失败 / 下载失败。
- 下载阶段单条失败只记录、不中断整个任务。
- 失败**不自动重试**（出现记录后即不再自动重处理，需人工介入重跑）。
- 空库起步，**无需迁移**历史 txt 数据。

## 3. 数据模型与状态机

新增依赖：`org.xerial:sqlite-jdbc`（嵌入式、零外部服务，契合 fat-jar 部署）。

单表 `sync_record`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | 主键 |
| `file_key` | TEXT NOT NULL UNIQUE | 顽鹿 fileKey，去重键 |
| `status` | TEXT NOT NULL | `DOWNLOADED` / `SYNCED` / `UPLOAD_FAILED` / `DOWNLOAD_FAILED` |
| `onelap_account` | TEXT | 来源账号（为多账号留口） |
| `file_size` | INTEGER | 文件大小（可选，便于排查） |
| `error_msg` | TEXT | 失败原因（仅失败时写） |
| `download_time` | INTEGER | 下载完成时间戳（毫秒） |
| `sync_time` | INTEGER | 上传成功时间戳（毫秒） |
| `created_at` | INTEGER | 记录创建时间戳（毫秒） |
| `updated_at` | INTEGER | 记录更新时间戳（毫秒） |

去重规则：凡 `file_key` 已存在**任意**记录（含 `*_FAILED`）的活动，下次任务一律跳过。

状态流转：

```
新活动（file_key 不在表中）
  └─ 下载
       ├─ 失败 → INSERT status=DOWNLOAD_FAILED, error_msg     （继续处理其它活动，不中断 job）
       └─ 成功 → INSERT status=DOWNLOADED, download_time, file_size

本轮所有 DOWNLOADED 文件 → 整批上传捷安特
  ├─ status==1   → UPDATE 这批为 SYNCED, sync_time
  └─ 否则        → UPDATE 这批为 UPLOAD_FAILED, error_msg
```

说明：捷安特上传接口 `files[]` 为整批提交、只返回一个 `status`，**拿不到单文件级成败**，故批内粒度只能统一到批级状态。

## 4. 组件改造

新增 `SyncRecordDao`（建议置于新建 `db` 包，或 `utils` 包）——唯一与数据库交互的类：

- `init()`：`CREATE TABLE IF NOT EXISTS` + `PRAGMA journal_mode=WAL`。
- `Set<String> findAllFileKeys()`：取所有已存在 fileKey，供去重（替代读 txt）。
- `insertDownloaded(fileKey, account, size)`
- `markDownloadFailed(fileKey, err)`
- `markSynced(List<String> fileKeys, syncTime)`
- `markUploadFailed(List<String> fileKeys, err)`
- 连接方式：SQLite 文件 + WAL，单连接常驻（Quartz `@DisallowConcurrentExecution` 已保证单写者），不引入连接池。

改造：

- `OnelapService.downloadTheOnelapFitFile()`：去重由 `dao.findAllFileKeys()` 提供；下载成功 `insertDownloaded`，下载失败 `markDownloadFailed` 后 `continue`。
- `GiantBikeService.syncFitFilesToGiantBike()`：上传成功 `markSynced`，失败 `markUploadFailed`（替代 `writeTxtFile`）。
- `Main`：进程启动时调用一次 `dao.init()`。

删除：`TxtOperationUtil`（无迁移需求，彻底移除）。

## 5. 配置变更（config.properties）

- 删除 `sync.fit.file.save.path`
- 新增 `sync.db.path`（如 `/home/aibot/yangyang/syncTheRecordingOfOnelapToGiant/sync_record.db`）

## 6. 打包与部署

- `sqlite-jdbc` 含多平台 native 库，maven-assembly fat-jar 会自动打入。
- 部署机为 Linux（`/home/aibot/...`），需在目标机实跑一次确认 native 库可加载；x86_64 一般无碍，若 ARM/Alpine(musl) 需复核。

## 7. 测试

- 用临时 .db 文件对 `SyncRecordDao` 做单测：建表 → 插入 → 去重 → 各状态流转，全程不依赖网络。

## 8. 风险与权衡

- 二进制 .db 不如 txt 可直接编辑查看，排查需借助 SQL/工具。
- "失败不重试"意味着瞬时网络故障也会让该活动停在失败态，直至人工重跑。
- native 库加载受部署平台架构影响（见 §6）。
- Java 8 兼容：`sqlite-jdbc` 支持 JDK8，无障碍。
