package com.dream.mryang.syncTheRecordingOfOnelapToGiant.db;

import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 同步记录数据访问层（SQLite）
 * <p>
 * 使用单连接 + WAL 模式，所有公开方法加 synchronized 保证线程安全。
 */
public class SyncRecordDao {

    private static final Logger log = LoggerFactory.getLogger(SyncRecordDao.class);

    // ===== 状态常量 =====
    public static final String STATUS_DOWNLOADED    = "DOWNLOADED";
    public static final String STATUS_SYNCED        = "SYNCED";
    public static final String STATUS_UPLOAD_FAILED = "UPLOAD_FAILED";
    public static final String STATUS_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";

    /** 常驻单连接 */
    private static Connection conn;

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS sync_record (" +
            "  id            INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  file_key      TEXT    NOT NULL UNIQUE," +
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
     * 查询所有已记录的 file_key（无论状态）。
     */
    public static synchronized Set<String> findAllFileKeys() {
        ensureInitialized();
        Set<String> keys = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT file_key FROM sync_record");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                keys.add(rs.getString("file_key"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findAllFileKeys 查询失败：" + e.getMessage(), e);
        }
        return keys;
    }

    // ===== 写入 =====

    /**
     * 插入一条下载成功记录（status=DOWNLOADED）。
     */
    public static synchronized void insertDownloaded(String fileKey, String account, long fileSize) {
        ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO sync_record" +
                "(file_key, status, onelap_account, file_size, download_time, created_at, updated_at)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?)";
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
            throw new RuntimeException("insertDownloaded 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 插入一条下载失败记录（status=DOWNLOAD_FAILED）。
     */
    public static synchronized void markDownloadFailed(String fileKey, String account, String errorMsg) {
        ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO sync_record" +
                "(file_key, status, onelap_account, error_msg, created_at, updated_at)" +
                " VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fileKey);
            ps.setString(2, STATUS_DOWNLOAD_FAILED);
            ps.setString(3, account);
            ps.setString(4, errorMsg);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("markDownloadFailed 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按 file_key 查询 status，主要供测试/排查用。无记录返回 null。
     */
    public static synchronized String findStatus(String fileKey) {
        ensureInitialized();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM sync_record WHERE file_key=?")) {
            ps.setString(1, fileKey);
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
     * 批量将指定 file_key 标记为 SYNCED。
     */
    public static synchronized void markSynced(List<String> fileKeys) {
        ensureInitialized();
        if (fileKeys == null || fileKeys.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sql = "UPDATE sync_record SET status=?, sync_time=?, updated_at=? WHERE file_key=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String key : fileKeys) {
                ps.setString(1, STATUS_SYNCED);
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setString(4, key);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("markSynced 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 批量将指定 file_key 标记为 UPLOAD_FAILED。
     */
    public static synchronized void markUploadFailed(List<String> fileKeys, String errorMsg) {
        ensureInitialized();
        if (fileKeys == null || fileKeys.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sql = "UPDATE sync_record SET status=?, error_msg=?, updated_at=? WHERE file_key=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String key : fileKeys) {
                ps.setString(1, STATUS_UPLOAD_FAILED);
                ps.setString(2, errorMsg);
                ps.setLong(3, now);
                ps.setString(4, key);
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
