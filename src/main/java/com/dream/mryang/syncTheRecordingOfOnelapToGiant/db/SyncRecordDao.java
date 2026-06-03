package com.dream.mryang.syncTheRecordingOfOnelapToGiant.db;

import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.HashSet;
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
