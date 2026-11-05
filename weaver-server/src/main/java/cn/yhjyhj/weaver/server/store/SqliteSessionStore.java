package cn.yhjyhj.weaver.server.store;

import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 使用单连接和 WAL 模式持久化会话元数据的 SQLite 实现。
 *
 * <p>{@link #backfill(Instant)} 会读取同一数据库文件中的 {@code session_sequences} 表，
 * 因此事件库与会话库必须指向同一个文件。两个存储各自持有独立连接，依赖 SQLite WAL
 * 支持并发访问。
 */
public final class SqliteSessionStore implements SessionStore, AutoCloseable {

    /** 持久化会话元数据表的语句。 */
    private static final String CREATE_SESSIONS = """
            CREATE TABLE IF NOT EXISTS sessions (
                id         TEXT PRIMARY KEY,
                title      TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                workspace  TEXT
            )
            """;

    /** 按最近更新时间列出会话。 */
    private static final String SELECT_SESSIONS = """
            SELECT id, title, created_at, workspace
            FROM sessions
            ORDER BY updated_at DESC, id
            """;

    /** 按标识读取单个会话。 */
    private static final String SELECT_SESSION =
            "SELECT id, title, created_at, workspace FROM sessions WHERE id = ?";

    /** 写入新会话元数据。 */
    private static final String INSERT_SESSION = """
            INSERT INTO sessions (id, title, created_at, updated_at)
            VALUES (?, ?, ?, ?)
            """;

    /** 手动修改标题并刷新更新时间。 */
    private static final String RENAME_SESSION = """
            UPDATE sessions
            SET title = ?, updated_at = ?
            WHERE id = ?
            """;

    /** 仅填充仍为空的标题并刷新更新时间。 */
    private static final String RENAME_UNNAMED_SESSION = """
            UPDATE sessions
            SET title = ?, updated_at = ?
            WHERE id = ? AND title IS NULL
            """;

    /** 修改会话工作目录并刷新更新时间。 */
    private static final String UPDATE_WORKSPACE = """
            UPDATE sessions
            SET workspace = ?, updated_at = ?
            WHERE id = ?
            """;

    /** 刷新最近更新时间。 */
    private static final String TOUCH_SESSION =
            "UPDATE sessions SET updated_at = ? WHERE id = ?";

    /** 删除会话元数据。 */
    private static final String DELETE_SESSION = "DELETE FROM sessions WHERE id = ?";

    /** 为已有事件序号但没有元数据的会话补行。 */
    private static final String BACKFILL_SESSIONS = """
            INSERT INTO sessions (id, title, created_at, updated_at)
            SELECT session_id, NULL, ?, ?
            FROM session_sequences s
            WHERE NOT EXISTS (SELECT 1 FROM sessions x WHERE x.id = s.session_id)
            """;

    /** 本实例独占的 SQLite 连接。 */
    private final Connection connection;
    /** 当前存储是否是连接私有的内存数据库。 */
    private final boolean memoryDatabase;
    /** 连接是否已经成功关闭。 */
    private boolean closed;

    /**
     * 打开或创建指定路径的 SQLite 会话库。
     *
     * <p>构造时创建父目录和会话表，并启用 WAL 与外键约束。目录创建、连接或建表失败时
     * 抛出 {@link IllegalStateException}。
     *
     * @param databasePath SQLite 数据库文件路径，或 {@code :memory:}
     */
    public SqliteSessionStore(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath");
        this.memoryDatabase = isMemory(databasePath);
        Connection opened = null;
        try {
            createParentDirectory(databasePath);
            opened = DriverManager.getConnection(jdbcUrl(databasePath));
            configure(opened);
            createSchema(opened);
            ensureWorkspaceColumn(opened);
            this.connection = opened;
        } catch (IOException | SQLException exception) {
            closeAfterConstructionFailure(opened);
            throw new IllegalStateException("failed to initialize SQLite session store", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void create(String id, String title, Instant createdAt) {
        ensureOpen();
        requireSessionId(id);
        long createdAtMillis = requireTimestamp(createdAt);

        try (PreparedStatement statement = connection.prepareStatement(INSERT_SESSION)) {
            statement.setString(1, id);
            statement.setString(2, title);
            statement.setLong(3, createdAtMillis);
            statement.setLong(4, createdAtMillis);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to create session metadata", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<SessionSummary> list() {
        ensureOpen();

        try (PreparedStatement statement = connection.prepareStatement(SELECT_SESSIONS);
                ResultSet result = statement.executeQuery()) {
            List<SessionSummary> sessions = new ArrayList<>();
            while (result.next()) {
                sessions.add(readSummary(result));
            }
            return List.copyOf(sessions);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to list session metadata", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<SessionSummary> find(String id) {
        ensureOpen();
        requireSessionId(id);

        try (PreparedStatement statement = connection.prepareStatement(SELECT_SESSION)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readSummary(result))
                        : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to find session metadata", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean rename(String id, String title) {
        ensureOpen();
        requireSessionId(id);
        requireTitle(title);
        return executeTitleUpdate(RENAME_SESSION, id, title);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean updateWorkspace(String id, String workspace) {
        ensureOpen();
        requireSessionId(id);
        if (workspace == null || workspace.isBlank()) {
            throw new IllegalArgumentException("workspace must not be blank");
        }

        try (PreparedStatement statement = connection.prepareStatement(UPDATE_WORKSPACE)) {
            statement.setString(1, workspace);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to update session workspace", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean renameIfUnnamed(String id, String title) {
        ensureOpen();
        requireSessionId(id);
        requireTitle(title);
        return executeTitleUpdate(RENAME_UNNAMED_SESSION, id, title);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void touch(String id) {
        ensureOpen();
        requireSessionId(id);

        try (PreparedStatement statement = connection.prepareStatement(TOUCH_SESSION)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to touch session metadata", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean delete(String id) {
        ensureOpen();
        requireSessionId(id);

        try (PreparedStatement statement = connection.prepareStatement(DELETE_SESSION)) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to delete session metadata", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized int backfill(Instant createdAt) {
        ensureOpen();
        // 两个 :memory: 连接互不可见，无法读取事件库的跨表数据。
        if (memoryDatabase) {
            return 0;
        }
        long timestamp = requireTimestamp(createdAt);

        try (PreparedStatement statement = connection.prepareStatement(BACKFILL_SESSIONS)) {
            statement.setLong(1, timestamp);
            statement.setLong(2, timestamp);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to backfill session metadata", exception);
        }
    }

    /**
     * 幂等关闭底层 SQLite 连接；关闭后的操作会抛出 {@link IllegalStateException}。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        try {
            connection.close();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to close SQLite session store", exception);
        } finally {
            closed = true;
        }
    }

    private boolean executeTitleUpdate(String sql, String id, String title) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to update session title", exception);
        }
    }

    private static SessionSummary readSummary(ResultSet result) throws SQLException {
        return new SessionSummary(
                result.getString("id"),
                result.getString("title"),
                Instant.ofEpochMilli(result.getLong("created_at")),
                result.getString("workspace"));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SQLite session store is closed");
        }
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
    }

    private static long requireTimestamp(Instant timestamp) {
        return Objects.requireNonNull(timestamp, "createdAt").toEpochMilli();
    }

    private static void createParentDirectory(Path databasePath) throws IOException {
        if (isMemory(databasePath)) {
            return;
        }
        Path absolutePath = databasePath.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String jdbcUrl(Path databasePath) {
        if (isMemory(databasePath)) {
            return "jdbc:sqlite::memory:";
        }
        return "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize()
                .toString()
                .replace('\\', '/');
    }

    private static boolean isMemory(Path databasePath) {
        return ":memory:".equals(databasePath.toString());
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_SESSIONS);
        }
    }

    private static void ensureWorkspaceColumn(Connection connection) throws SQLException {
        boolean hasWorkspace = false;
        try (Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery("PRAGMA table_info(sessions)")) {
            while (columns.next()) {
                if ("workspace".equalsIgnoreCase(columns.getString("name"))) {
                    hasWorkspace = true;
                    break;
                }
            }
        }
        if (!hasWorkspace) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE sessions ADD COLUMN workspace TEXT");
            }
        }
    }

    private static void closeAfterConstructionFailure(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 保留初始化异常，避免掩盖真正的连接或建表失败原因。
        }
    }
}
