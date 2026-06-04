# 引入 SQLite 维护同步记录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将"已同步 fit 文件记录"从平文本 txt 迁移到嵌入式 SQLite，并扩展为完整生命周期记录（已下载/已同步/上传失败/下载失败）。

**Architecture:** 新增 `SyncRecordDao`（静态方法 + 常驻单连接 + WAL）作为唯一的数据库访问层。`OnelapService` 用 `findAllFileKeys()` 去重、下载成功/失败分别写记录；`GiantBikeService` 上传成功/失败更新记录；`Main` 启动时建表。删除 `TxtOperationUtil`。空库起步，无历史迁移。

**Tech Stack:** Java 8, Maven, `org.xerial:sqlite-jdbc`, JUnit 4, SLF4J/Logback, Quartz。

设计文档：`docs/superpowers/specs/2026-06-03-sqlite-sync-record-design.md`

---

## 文件结构

- 新建 `src/main/java/.../db/SyncRecordDao.java` —— 唯一数据库访问层（建表、去重查询、状态写入）。
- 新建 `src/test/java/.../db/SyncRecordDaoTest.java` —— 基于临时 .db 文件的单测。
- 修改 `pom.xml` —— 增加 sqlite-jdbc 依赖。
- 修改 `src/main/java/.../service/OnelapService.java` —— 去重与下载记录。
- 修改 `src/main/java/.../service/GiantBikeService.java` —— 上传成功/失败记录。
- 修改 `src/main/java/.../Main.java` —— 启动时建表。
- 修改 `src/main/resources/config.properties` —— 去掉 `sync.fit.file.save.path`，新增 `sync.db.path`。
- 删除 `src/main/java/.../utils/TxtOperationUtil.java`。

包根：`com.dream.mryang.syncTheRecordingOfOnelapToGiant`（下文用 `<pkg>` 代指 `src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant`）。

---

## Task 1: 引入 sqlite-jdbc 依赖

**Files:**
- Modify: `pom.xml`（`<dependencies>` 内，Quartz 依赖之后）

- [ ] **Step 1: 添加依赖**

在 `pom.xml` 的 Quartz 依赖（`</dependency>` 行，约第 68 行）之后、`</dependencies>` 之前插入：

```xml
        <!-- SQLite (嵌入式，含多平台 native 库) -->
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>3.45.3.0</version>
        </dependency>
```

- [ ] **Step 2: 验证依赖可解析**

Run: `mvn -q dependency:resolve`
Expected: 构建成功，无法解析时改用最近的可用版本（如 `3.44.1.0`）并记录。

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: 引入 sqlite-jdbc 依赖"
```

---

## Task 2: SyncRecordDao —— 建表与去重查询

**Files:**
- Create: `<pkg>/db/SyncRecordDao.java`
- Test: `src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDaoTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDaoTest.java`：

```java
package com.dream.mryang.syncTheRecordingOfOnelapToGiant.db;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Set;

import static org.junit.Assert.*;

public class SyncRecordDaoTest {

    private String dbPath;

    @Before
    public void setUp() throws Exception {
        File tmp = File.createTempFile("sync_record_test", ".db");
        // File.createTempFile 会建出空文件，删除后让 SQLite 自行初始化
        assertTrue(tmp.delete());
        dbPath = tmp.getAbsolutePath();
        SyncRecordDao.init(dbPath);
    }

    @Test
    public void emptyDbReturnsNoKeys() {
        Set<String> keys = SyncRecordDao.findAllFileKeys();
        assertTrue(keys.isEmpty());
    }

    @Test
    public void initIsIdempotent() {
        // 再次 init 不应抛异常（CREATE TABLE IF NOT EXISTS）
        SyncRecordDao.init(dbPath);
        assertTrue(SyncRecordDao.findAllFileKeys().isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=SyncRecordDaoTest test`
Expected: 编译失败（`SyncRecordDao` 不存在）。

- [ ] **Step 3: 实现 SyncRecordDao（建表 + 去重查询）**

创建 `<pkg>/db/SyncRecordDao.java`：

```java
package com.dream.mryang.syncTheRecordingOfOnelapToGiant.db;

import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * 同步记录数据访问层（唯一与 SQLite 交互的类）。
 * 单连接常驻 + WAL；Quartz @DisallowConcurrentExecution 已保证单写者，方法统一加 synchronized。
 */
public class SyncRecordDao {
    private static final Logger log = LoggerFactory.getLogger(SyncRecordDao.class);

    public static final String STATUS_DOWNLOADED = "DOWNLOADED";
    public static final String STATUS_SYNCED = "SYNCED";
    public static final String STATUS_UPLOAD_FAILED = "UPLOAD_FAILED";
    public static final String STATUS_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";

    private static Connection conn;

    /** 从配置读取库路径并初始化。 */
    public static synchronized void init() {
        init(ConfigManager.getProperty("sync.db.path"));
    }

    /** 指定库路径初始化（供测试使用）。 */
    public static synchronized void init(String dbPath) {
        try {
            File dbFile = new File(dbPath);
            File parent = dbFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new RuntimeException("创建数据库父级目录失败：" + parent);
            }
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("CREATE TABLE IF NOT EXISTS sync_record (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "file_key TEXT NOT NULL UNIQUE," +
                        "status TEXT NOT NULL," +
                        "onelap_account TEXT," +
                        "file_size INTEGER," +
                        "error_msg TEXT," +
                        "download_time INTEGER," +
                        "sync_time INTEGER," +
                        "created_at INTEGER NOT NULL," +
                        "updated_at INTEGER NOT NULL)");
            }
            log.info("SQLite 同步记录库初始化完成：{}", dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("初始化 SQLite 数据库失败：" + dbPath, e);
        }
    }

    /** 取所有已存在 fileKey（含失败态），供去重。 */
    public static synchronized Set<String> findAllFileKeys() {
        Set<String> keys = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT file_key FROM sync_record")) {
            while (rs.next()) {
                keys.add(rs.getString("file_key"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询同步记录失败", e);
        }
        return keys;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=SyncRecordDaoTest test`
Expected: PASS（2 个测试通过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDao.java src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDaoTest.java
git commit -m "feat: SyncRecordDao 支持建表与去重查询"
```

---

## Task 3: SyncRecordDao —— 写入下载成功/失败记录

**Files:**
- Modify: `<pkg>/db/SyncRecordDao.java`
- Test: `src/test/java/.../db/SyncRecordDaoTest.java`

- [ ] **Step 1: 写失败测试**

在 `SyncRecordDaoTest` 类中追加：

```java
    @Test
    public void insertDownloadedThenFoundInKeys() {
        SyncRecordDao.insertDownloaded("key-a", "acc1", 1234L);
        Set<String> keys = SyncRecordDao.findAllFileKeys();
        assertEquals(1, keys.size());
        assertTrue(keys.contains("key-a"));
    }

    @Test
    public void markDownloadFailedAlsoCountsForDedup() {
        SyncRecordDao.markDownloadFailed("key-b", "acc1", "网络超时");
        Set<String> keys = SyncRecordDao.findAllFileKeys();
        assertTrue(keys.contains("key-b"));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=SyncRecordDaoTest test`
Expected: 编译失败（`insertDownloaded` / `markDownloadFailed` 不存在）。

- [ ] **Step 3: 实现两个方法**

在 `SyncRecordDao` 中（`findAllFileKeys` 之后）添加 import 与方法：

import 区追加：
```java
import java.sql.PreparedStatement;
```

方法：
```java
    /** 下载成功：插入 DOWNLOADED 记录。 */
    public static synchronized void insertDownloaded(String fileKey, String account, long fileSize) {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO sync_record " +
                "(file_key,status,onelap_account,file_size,download_time,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fileKey);
            ps.setString(2, STATUS_DOWNLOADED);
            ps.setString(3, account);
            ps.setLong(4, fileSize);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("写入下载记录失败：" + fileKey, e);
        }
    }

    /** 下载失败：插入 DOWNLOAD_FAILED 记录。 */
    public static synchronized void markDownloadFailed(String fileKey, String account, String errorMsg) {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO sync_record " +
                "(file_key,status,onelap_account,error_msg,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fileKey);
            ps.setString(2, STATUS_DOWNLOAD_FAILED);
            ps.setString(3, account);
            ps.setString(4, errorMsg);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("写入下载失败记录失败：" + fileKey, e);
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=SyncRecordDaoTest test`
Expected: PASS（4 个测试通过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDao.java src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDaoTest.java
git commit -m "feat: SyncRecordDao 支持写入下载成功/失败记录"
```

---

## Task 4: SyncRecordDao —— 标记上传成功/失败

**Files:**
- Modify: `<pkg>/db/SyncRecordDao.java`
- Test: `src/test/java/.../db/SyncRecordDaoTest.java`

- [ ] **Step 1: 写失败测试**

在 `SyncRecordDaoTest` 中追加（新增方法用于断言状态，故测试直接查 status）：

```java
    @Test
    public void markSyncedUpdatesStatus() {
        SyncRecordDao.insertDownloaded("key-c", "acc1", 10L);
        SyncRecordDao.markSynced(Arrays.asList("key-c"));
        assertEquals(SyncRecordDao.STATUS_SYNCED, SyncRecordDao.findStatus("key-c"));
    }

    @Test
    public void markUploadFailedUpdatesStatus() {
        SyncRecordDao.insertDownloaded("key-d", "acc1", 10L);
        SyncRecordDao.markUploadFailed(Arrays.asList("key-d"), "捷安特返回 status=0");
        assertEquals(SyncRecordDao.STATUS_UPLOAD_FAILED, SyncRecordDao.findStatus("key-d"));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=SyncRecordDaoTest test`
Expected: 编译失败（`markSynced` / `markUploadFailed` / `findStatus` 不存在）。

- [ ] **Step 3: 实现三个方法**

在 `SyncRecordDao` 中追加 import 与方法：

import 区追加：
```java
import java.util.List;
```

方法：
```java
    /** 上传成功：批量更新为 SYNCED。 */
    public static synchronized void markSynced(List<String> fileKeys) {
        long now = System.currentTimeMillis();
        String sql = "UPDATE sync_record SET status=?, sync_time=?, updated_at=? WHERE file_key=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String fileKey : fileKeys) {
                ps.setString(1, STATUS_SYNCED);
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setString(4, fileKey);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("更新同步成功记录失败", e);
        }
    }

    /** 上传失败：批量更新为 UPLOAD_FAILED。 */
    public static synchronized void markUploadFailed(List<String> fileKeys, String errorMsg) {
        long now = System.currentTimeMillis();
        String sql = "UPDATE sync_record SET status=?, error_msg=?, updated_at=? WHERE file_key=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String fileKey : fileKeys) {
                ps.setString(1, STATUS_UPLOAD_FAILED);
                ps.setString(2, errorMsg);
                ps.setLong(3, now);
                ps.setString(4, fileKey);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("更新上传失败记录失败", e);
        }
    }

    /** 查询单条记录状态（主要供测试/排查使用）。 */
    public static synchronized String findStatus(String fileKey) {
        String sql = "SELECT status FROM sync_record WHERE file_key=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fileKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询记录状态失败：" + fileKey, e);
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=SyncRecordDaoTest test`
Expected: PASS（6 个测试通过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDao.java src/test/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/db/SyncRecordDaoTest.java
git commit -m "feat: SyncRecordDao 支持标记上传成功/失败"
```

---

## Task 5: OnelapService 接入去重与下载记录

**Files:**
- Modify: `<pkg>/service/OnelapService.java`

说明：本服务依赖网络登录，不做单测；通过编译 + 后续手测验证。

- [ ] **Step 1: 替换 import**

删除：
```java
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.TxtOperationUtil;
```
新增：
```java
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.db.SyncRecordDao;
import java.util.Set;
```

- [ ] **Step 2: 用 DB 去重替换读 txt**

将（约第 70-75 行）：
```java
        ArrayList<String> list = TxtOperationUtil.readTxtFile(ConfigManager.getProperty("sync.fit.file.save.path"));
        ArrayList<String> syncFileName = new ArrayList<>();
        List<JSONObject> myActivityObjectList = myActivities.stream().limit(endIndex)
                .map(a -> (JSONObject) a)
                .filter(jsonObject -> !list.contains(jsonObject.getString("fileKey")))
                .collect(Collectors.toList());
```
替换为：
```java
        Set<String> syncedKeys = SyncRecordDao.findAllFileKeys();
        ArrayList<String> syncFileName = new ArrayList<>();
        List<JSONObject> myActivityObjectList = myActivities.stream().limit(endIndex)
                .map(a -> (JSONObject) a)
                .filter(jsonObject -> !syncedKeys.contains(jsonObject.getString("fileKey")))
                .collect(Collectors.toList());
```

- [ ] **Step 3: 下载循环隔离失败并写记录**

将（约第 77-83 行）：
```java
        for (JSONObject jsonObject : myActivityObjectList) {
            String fileKey = jsonObject.getString("fileKey");
            String durl = jsonObject.getString("durl");
            File file = new File(ConfigManager.getProperty("onelap.fit.file.storage.directory") + fileKey);
            HttpClientUtil.downloadFile(durl, file);
            syncFileName.add(fileKey);
        }
        return syncFileName;
```
替换为：
```java
        for (JSONObject jsonObject : myActivityObjectList) {
            String fileKey = jsonObject.getString("fileKey");
            String durl = jsonObject.getString("durl");
            File file = new File(ConfigManager.getProperty("onelap.fit.file.storage.directory") + fileKey);
            try {
                HttpClientUtil.downloadFile(durl, file);
                SyncRecordDao.insertDownloaded(fileKey, account, file.length());
                syncFileName.add(fileKey);
            } catch (Exception e) {
                log.error("下载活动文件失败，跳过该条：{}", fileKey, e);
                SyncRecordDao.markDownloadFailed(fileKey, account, e.getMessage());
            }
        }
        return syncFileName;
```

- [ ] **Step 4: 编译验证**

Run: `mvn -q compile`
Expected: 编译成功。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/service/OnelapService.java
git commit -m "feat: OnelapService 用 SQLite 去重并记录下载结果"
```

---

## Task 6: GiantBikeService 接入上传记录

**Files:**
- Modify: `<pkg>/service/GiantBikeService.java`

- [ ] **Step 1: 替换 import**

删除：
```java
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.TxtOperationUtil;
```
新增：
```java
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.db.SyncRecordDao;
```

- [ ] **Step 2: 用 try/catch 包裹上传并改记录方式**

将方法体（约第 24-60 行，从 `if (fitFileNameList == null...` 之后到方法结束）替换为：
```java
        if (fitFileNameList == null || fitFileNameList.isEmpty()) {
            return;
        }

        try {
            List<NameValuePair> formParams = new ArrayList<>();
            formParams.add(new BasicNameValuePair("username", ConfigManager.getProperty("giant.username")));
            formParams.add(new BasicNameValuePair("password", ConfigManager.getProperty("giant.password")));

            String loginReturnJsonString = HttpClientUtil.doPostJson(SyncConstants.GIANT_LOGIN_URL, null, formParams, null, null);
            log.info("调 捷安特骑行登录 接口响应值：{}", loginReturnJsonString);

            JSONObject loginReturnData = JSONObject.parseObject(loginReturnJsonString);
            String userToken = loginReturnData.getString("user_token");

            MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
            for (String fitFileName : fitFileNameList) {
                File file = new File(ConfigManager.getProperty("onelap.fit.file.storage.directory") + fitFileName);
                multipartEntityBuilder.addBinaryBody("files[]", file, ContentType.DEFAULT_BINARY, file.getName());
            }
            ContentType CONTENT_TYPE = ContentType.create("text/plain", Consts.UTF_8);
            multipartEntityBuilder.addPart("token", new StringBody(userToken, CONTENT_TYPE));
            multipartEntityBuilder.addPart("device", new StringBody(SyncConstants.GIANT_DEVICE, CONTENT_TYPE));
            multipartEntityBuilder.addPart("brand", new StringBody(SyncConstants.GIANT_BRAND, CONTENT_TYPE));

            String respondJson = HttpClientUtil.doPostJson(SyncConstants.GIANT_UPLOAD_FIT_URL, null, null, multipartEntityBuilder, null);
            log.info("调 捷安特上传文件 接口响应值：{}", respondJson);

            JSONObject respondJsonData = JSONObject.parseObject(respondJson);
            Integer status = respondJsonData.getInteger("status");
            if (status != null && status == 1) {
                SyncRecordDao.markSynced(fitFileNameList);
                log.info("【完成】同步数量：{}", fitFileNameList.size());
            } else {
                SyncRecordDao.markUploadFailed(fitFileNameList, respondJson);
                log.error("调用接口上传文件响应异常，异常信息：{}", respondJson);
            }
        } catch (Exception e) {
            log.error("同步捷安特上传过程异常，本批标记为上传失败", e);
            SyncRecordDao.markUploadFailed(fitFileNameList, e.getMessage());
        }
```

- [ ] **Step 3: 编译验证**

Run: `mvn -q compile`
Expected: 编译成功。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/service/GiantBikeService.java
git commit -m "feat: GiantBikeService 用 SQLite 记录上传成功/失败"
```

---

## Task 7: Main 启动时初始化数据库

**Files:**
- Modify: `<pkg>/Main.java`

- [ ] **Step 1: 新增 import**

在 import 区添加：
```java
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.db.SyncRecordDao;
```

- [ ] **Step 2: main() 开头调用 init()**

将（约第 26-27 行）：
```java
    public static void main(String[] args) throws SchedulerException {
        // 创建JobDetail实例
        JobDetail job = JobBuilder.newJob(TaskJob.class).build();
```
替换为：
```java
    public static void main(String[] args) throws SchedulerException {
        // 初始化同步记录数据库（建表）
        SyncRecordDao.init();

        // 创建JobDetail实例
        JobDetail job = JobBuilder.newJob(TaskJob.class).build();
```

- [ ] **Step 3: 编译验证**

Run: `mvn -q compile`
Expected: 编译成功。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/Main.java
git commit -m "feat: Main 启动时初始化 SQLite 同步记录库"
```

---

## Task 8: 配置变更与删除 TxtOperationUtil

**Files:**
- Modify: `src/main/resources/config.properties`
- Delete: `<pkg>/utils/TxtOperationUtil.java`

- [ ] **Step 1: 改配置**

在 `config.properties` 中删除这 3 行（约第 21-23 行）：
```properties
# 已同步fit文件记录存储txt文件路径
#sync.fit.file.save.path=E:\\onelapFitFileStorageDirecotry\\syncFitFileSaveFile.txt
sync.fit.file.save.path=/home/aibot/yangyang/syncTheRecordingOfOnelapToGiant/onelapFitFileStorageDirecotry/syncFitFileSaveFile.txt
```
替换为：
```properties
# SQLite 同步记录库文件路径
#sync.db.path=E:\\onelapFitFileStorageDirecotry\\sync_record.db
sync.db.path=/home/aibot/yangyang/syncTheRecordingOfOnelapToGiant/sync_record.db
```

- [ ] **Step 2: 删除 TxtOperationUtil**

```bash
git rm src/main/java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/utils/TxtOperationUtil.java
```

- [ ] **Step 3: 全量编译 + 测试 + 打包验证**

Run: `mvn -q clean package`
Expected: 编译/测试/打包全部成功，`target/` 下生成可执行 fat-jar；确认无对 `TxtOperationUtil` 的残留引用导致编译失败。

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/config.properties
git commit -m "chore: 配置改用 sync.db.path 并删除 TxtOperationUtil"
```

---

## Task 9: 部署验证（手动）

**Files:** 无代码改动。

- [ ] **Step 1: 目标机实跑确认 native 库可加载**

在部署机（Linux `/home/aibot/...`）运行 fat-jar，确认：
- 启动日志出现"SQLite 同步记录库初始化完成"；
- `sync.db.path` 指向位置生成 `.db` 文件（及 WAL 边车文件）；
- 跑一轮同步后，用 `sqlite3 sync_record.db "SELECT status,count(*) FROM sync_record GROUP BY status;"` 查看状态分布。

若部署机为 ARM/Alpine(musl) 且 native 库加载失败，按 sqlite-jdbc 文档切换匹配的 native profile 版本。

- [ ] **Step 2: 无需提交**（验证步骤）

---

## Self-Review 记录

- **Spec 覆盖**：§3 数据模型→Task 2 建表；去重→Task 2/5；下载成功失败→Task 3/5；上传成功失败→Task 4/6；§4 组件改造→Task 5/6/7；§5 配置→Task 8；§6 打包/部署→Task 1/8/9；§7 测试→Task 2/3/4。无遗漏。
- **失败不重试**：去重用 `findAllFileKeys()`（含失败态），任何已有记录的 fileKey 不再处理，符合语义。
- **类型一致**：`markSynced(List<String>)`、`markUploadFailed(List<String>, String)`、`insertDownloaded(String,String,long)`、`markDownloadFailed(String,String,String)`、`findAllFileKeys():Set<String>`、`findStatus(String):String` 在各任务间保持一致。
- **补充决策**：上传阶段异常（非 status!=1）也统一 `markUploadFailed`，避免记录卡在 DOWNLOADED 永不重处理（Task 6）。
