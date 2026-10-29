package cn.yhjyhj.weaver.server.store;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 使用单连接和 WAL 模式持久化会话事件的 SQLite 实现。 */
public final class SqliteEventStore implements SessionEventStore, AutoCloseable {

    /** 持久化 seq 分配状态表的语句。 */
    private static final String CREATE_SEQUENCES = """
            CREATE TABLE IF NOT EXISTS session_sequences (
                session_id TEXT PRIMARY KEY,
                last_seq   INTEGER NOT NULL
            )
            """;

    /** 持久化事件事实表的语句。 */
    private static final String CREATE_EVENTS = """
            CREATE TABLE IF NOT EXISTS session_events (
                session_id TEXT    NOT NULL,
                seq        INTEGER NOT NULL,
                event_type TEXT    NOT NULL,
                payload    TEXT    NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (session_id, seq)
            )
            """;

    /** 为指定会话原子递增并返回下一个 seq。 */
    private static final String ALLOCATE_SEQUENCE = """
            INSERT INTO session_sequences (session_id, last_seq)
            VALUES (?, 1)
            ON CONFLICT(session_id) DO UPDATE SET last_seq = last_seq + 1
            RETURNING last_seq
            """;

    /** 写入一条已编码事件。 */
    private static final String INSERT_EVENT = """
            INSERT INTO session_events (session_id, seq, event_type, payload, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    /** 按序号读取会话事件。 */
    private static final String SELECT_EVENTS = """
            SELECT seq, payload
            FROM session_events
            WHERE session_id = ? AND seq > ?
            ORDER BY seq
            """;

    /** 查询会话当前最后序号。 */
    private static final String SELECT_LAST_SEQ =
            "SELECT last_seq FROM session_sequences WHERE session_id = ?";

    /** 本实例独占的 SQLite 连接。 */
    private final Connection connection;
    /** 事件 JSON 编解码器。 */
    private final SessionEventCodec codec; // 无状态，可安全复用
    /** 连接是否已经成功关闭。 */
    private boolean closed;

    /**
     * 打开或创建指定路径的 SQLite 事件库。
     *
     * <p>构造时创建父目录和数据表，并启用 WAL 与外键约束。目录创建、连接或建表失败时
     * 抛出 {@link IllegalStateException}。
     *
     * @param databasePath SQLite 数据库文件路径，或 {@code :memory:}
     */
    public SqliteEventStore(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath");
        Connection opened = null;
        try {
            createParentDirectory(databasePath);
            opened = DriverManager.getConnection(jdbcUrl(databasePath));
            configure(opened);
            createSchema(opened);
            this.connection = opened;
            this.codec = new SessionEventCodec();
        } catch (IOException | SQLException exception) {
            closeAfterConstructionFailure(opened);
            throw new IllegalStateException("failed to initialize SQLite event store", exception);
        }
    }

    /**
     * 在同一事务中分配会话内单调 seq、写入事件并提交。
     *
     * @param sessionId 非空会话标识
     * @param event 待持久化事件
     * @return 新事件的 seq
     * @throws IllegalArgumentException 会话或事件无效时抛出
     * @throws IllegalStateException 存储已关闭或写入失败时抛出
     */
    @Override
    public synchronized long append(String sessionId, SessionEvent event) {
        ensureOpen();
        requireSessionId(sessionId);
        String payload = codec.encode(event);

        try {
            connection.setAutoCommit(false);
            long seq;
            try (PreparedStatement sequenceStatement =
                            connection.prepareStatement(ALLOCATE_SEQUENCE);
                    PreparedStatement eventStatement =
                            connection.prepareStatement(INSERT_EVENT)) {
                sequenceStatement.setString(1, sessionId);
                try (ResultSet result = sequenceStatement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("SQLite did not return an allocated sequence");
                    }
                    seq = result.getLong("last_seq");
                }

                eventStatement.setString(1, sessionId);
                eventStatement.setLong(2, seq);
                eventStatement.setString(3, codec.typeOf(event));
                eventStatement.setString(4, payload);
                eventStatement.setLong(5, System.currentTimeMillis());
                eventStatement.executeUpdate();
            }
            connection.commit();
            return seq;
        } catch (SQLException exception) {
            rollback();
            throw new IllegalStateException("failed to append session event", exception);
        } finally {
            restoreAutoCommit();
        }
    }

    /**
     * 返回指定会话中 seq 严格大于 {@code afterSeq} 的事件，按 seq 升序排列。
     *
     * @param sessionId 会话标识
     * @param afterSeq 已读取的序号边界
     * @return 不可修改的事件列表
     * @throws IllegalStateException 存储已关闭或读取失败时抛出
     */
    @Override
    public synchronized List<StoredEvent> from(String sessionId, long afterSeq) {
        ensureOpen();
        requireSessionId(sessionId);

        try (PreparedStatement statement = connection.prepareStatement(SELECT_EVENTS)) {
            statement.setString(1, sessionId);
            statement.setLong(2, afterSeq);
            try (ResultSet result = statement.executeQuery()) {
                List<StoredEvent> events = new ArrayList<>();
                while (result.next()) {
                    events.add(new StoredEvent(
                            result.getLong("seq"),
                            codec.decode(result.getString("payload"))));
                }
                return List.copyOf(events);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read session events", exception);
        }
    }

    /**
     * 返回指定会话已分配的最大 seq；没有事件时返回 0。
     *
     * @param sessionId 会话标识
     * @return 最后分配的 seq，或 0
     * @throws IllegalStateException 存储已关闭或读取失败时抛出
     */
    @Override
    public synchronized long lastSeq(String sessionId) {
        ensureOpen();
        requireSessionId(sessionId);

        try (PreparedStatement statement = connection.prepareStatement(SELECT_LAST_SEQ)) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong("last_seq") : 0L;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read last session sequence", exception);
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
            throw new IllegalStateException("failed to close SQLite event store", exception);
        } finally {
            closed = true;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SQLite event store is closed");
        }
    }

    private void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始写入异常；事务连接随后会恢复自动提交并拒绝后续无效写入。
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 原异常已携带主要失败原因；连接不可用时下一次操作会再次报告 SQL 错误。
        }
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
            statement.execute(CREATE_SEQUENCES);
            statement.execute(CREATE_EVENTS);
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
