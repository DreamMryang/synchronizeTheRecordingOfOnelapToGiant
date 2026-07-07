package com.dream.mryang.syncTheRecordingOfOnelapToGiant.db;

import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步记录数据访问层（SQLite）
 * <p>
 * 使用单连接 + WAL 模式，所有公开方法加 synchronized 保证线程安全。
 * <p>
 * 去重职责已移交捷安特服务端（见 docs/design/multi-client-sync.md），本库仅记账/排查：
 * 记录本机的下载/同步操作历史，并在每次同步开头依据服务端 all_upload 结果 reconcile。
 * 写入均为 upsert，以支持失败后的自然重试（同一 fit_url 不再撞 UNIQUE 约束）。
 */
public class SyncRecordDao {

    private static final Logger log = LoggerFactory.getLogger(SyncRecordDao.class);

    // ===== 状态常量 =====
    public static final String STATUS_DOWNLOADED    = "DOWNLOADED";
    public static final String STATUS_SYNCED        = "SYNCED";
    public static final String STATUS_UPLOAD_FAILED = "UPLOAD_FAILED";
    public static final String STATUS_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";
    /** 已上传但服务端处理失败（all_upload 判定）；不自动重传，需人工处理。 */
    public static final String STATUS_PROCESS_FAILED = "PROCESS_FAILED";

    /** 常驻单连接 */
    private static Connection conn;

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS sync_record (" +
            "  id            INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  fit_url       TEXT    NOT NULL UNIQUE," +
            "  status        TEXT    NOT NULL," +
            "  onelap_account TEXT," +
            "  file_size     INTEGER," +
            "  error_msg     TEXT," +
            "  download_time INTEGER," +
            "  sync_time     INTEGER," +
            "  created_at    INTEGER NOT NULL," +
            "  updated_at    INTEGER NOT NULL" +
            ")";

    private SyncRecordDao() {}

    // ===== init =====

    /**
     * 从 config.properties 读取 sync.db.path 并初始化。
     */
    public static synchronized void init() {
        init(ConfigManager.getProperty("sync.db.path"));
    }

    /**
     * 使用指定路径初始化 SQLite 连接和表结构。
     * <ul>
     *   <li>确保父目录存在（不存在则 mkdirs，失败抛 RuntimeException）</li>
     *   <li>启用 WAL 模式</li>
     *   <li>CREATE TABLE IF NOT EXISTS</li>
     * </ul>
     */
    public static synchronized void init(String dbPath) {
        // 确保父目录存在
        File dbFile = new File(dbPath);
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new RuntimeException("无法创建数据库父目录：" + parentDir.getAbsolutePath());
            }
        }

        try {
            // 若已有连接且未关闭，先关闭（支持重新 init 到新路径）
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            // 启用 WAL
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute(CREATE_TABLE_SQL);
            }
            log.info("SyncRecordDao 初始化完成，数据库路径：{}", dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("SyncRecordDao 初始化失败：" + e.getMessage(), e);
        }
    }

    // ===== 查询 =====

    /**
     * 查询可 reconcile 的记录：本机标记为 SYNCED / UPLOAD_FAILED 的 fit_url → status。
     * <p>
     * 依据服务端 all_upload 结果校正这些本机状态（真正处理成功 / 处理失败），
     * 见 {@code SyncLogic.reconcileLocal}。
     */
    public static synchronized Map<String, String> findReconcilable() {
        ensureInitialized();
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT fit_url, status FROM sync_record WHERE status IN (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, STATUS_SYNCED);
            ps.setString(2, STATUS_UPLOAD_FAILED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("fit_url"), rs.getString("status"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("findReconcilable 查询失败：" + e.getMessage(), e);
        }
        return result;
    }

    // ===== 写入 =====

    /**
     * upsert 一条下载成功记录（status=DOWNLOADED）。
     * <p>
     * 同一 fit_url 已存在时（如上次下载/上传失败后本次自然重试）覆盖更新：
     * 状态回 DOWNLOADED、清空 error_msg、刷新 file_size / download_time。
     */
    public static synchronized void upsertDownloaded(String fitUrl, String account, long fileSize) {
        ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO sync_record" +
                "(fit_url, status, onelap_account, file_size, error_msg, download_time, created_at, updated_at)" +
                " VALUES (?, ?, ?, ?, NULL, ?, ?, ?)" +
                " ON CONFLICT(fit_url) DO UPDATE SET" +
                "  status=excluded.status, onelap_account=excluded.onelap_account," +
                "  file_size=excluded.file_size, error_msg=NULL," +
                "  download_time=excluded.download_time, updated_at=excluded.updated_at";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fitUrl);
            ps.setString(2, STATUS_DOWNLOADED);
            ps.setString(3, account);
            ps.setLong(4, fileSize);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("upsertDownloaded 失败：" + e.getMessage(), e);
        }
    }

    /**
     * upsert 一条下载失败记录（status=DOWNLOAD_FAILED）。同 fit_url 已存在时覆盖更新状态与 error_msg。
     */
    public static synchronized void upsertDownloadFailed(String fitUrl, String account, String errorMsg) {
        ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO sync_record" +
                "(fit_url, status, onelap_account, error_msg, created_at, updated_at)" +
                " VALUES (?, ?, ?, ?, ?, ?)" +
                " ON CONFLICT(fit_url) DO UPDATE SET" +
                "  status=excluded.status, onelap_account=excluded.onelap_account," +
                "  error_msg=excluded.error_msg, updated_at=excluded.updated_at";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fitUrl);
            ps.setString(2, STATUS_DOWNLOAD_FAILED);
            ps.setString(3, account);
            ps.setString(4, errorMsg);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("upsertDownloadFailed 失败：" + e.getMessage(), e);
        }
    }

    /**
     * reconcile 校正单条记录状态与 error_msg。首次转为 SYNCED（原无 sync_time）时补 sync_time。
     */
    public static synchronized void updateStatus(String fitUrl, String status, String errorMsg) {
        ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = "UPDATE sync_record SET status=?, error_msg=?, updated_at=?," +
                "  sync_time = CASE WHEN ?='" + STATUS_SYNCED + "' AND sync_time IS NULL THEN ? ELSE sync_time END" +
                " WHERE fit_url=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, errorMsg);
            ps.setLong(3, now);
            ps.setString(4, status);
            ps.setLong(5, now);
            ps.setString(6, fitUrl);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("updateStatus 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按 fit_url 查询 status，主要供测试/排查用。无记录返回 null。
     */
    public static synchronized String findStatus(String fitUrl) {
        ensureInitialized();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM sync_record WHERE fit_url=?")) {
            ps.setString(1, fitUrl);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("status");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("findStatus 查询失败：" + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 按 fit_url 查询 sync_time，供测试/排查用。无记录或未同步返回 null。
     */
    public static synchronized Long findSyncTime(String fitUrl) {
        ensureInitialized();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sync_time FROM sync_record WHERE fit_url=?")) {
            ps.setString(1, fitUrl);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong("sync_time");
                    return rs.wasNull() ? null : v;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("findSyncTime 查询失败：" + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 批量将指定 fit_url 标记为 SYNCED。
     */
    public static synchronized void markSynced(List<String> fitUrls) {
        ensureInitialized();
        if (fitUrls == null || fitUrls.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sql = "UPDATE sync_record SET status=?, sync_time=?, updated_at=? WHERE fit_url=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String url : fitUrls) {
                ps.setString(1, STATUS_SYNCED);
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setString(4, url);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("markSynced 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 批量将指定 fit_url 标记为 UPLOAD_FAILED。
     */
    public static synchronized void markUploadFailed(List<String> fitUrls, String errorMsg) {
        ensureInitialized();
        if (fitUrls == null || fitUrls.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sql = "UPDATE sync_record SET status=?, error_msg=?, updated_at=? WHERE fit_url=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String url : fitUrls) {
                ps.setString(1, STATUS_UPLOAD_FAILED);
                ps.setString(2, errorMsg);
                ps.setLong(3, now);
                ps.setString(4, url);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("markUploadFailed 失败：" + e.getMessage(), e);
        }
    }

    // ===== 内部工具 =====

    private static void ensureInitialized() {
        try {
            if (conn == null || conn.isClosed()) {
                throw new IllegalStateException("SyncRecordDao 未初始化，请先调用 init()");
            }
        } catch (SQLException e) {
            throw new RuntimeException("检查连接状态失败：" + e.getMessage(), e);
        }
    }
}
