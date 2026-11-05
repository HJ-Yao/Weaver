package cn.yhjyhj.weaver.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqliteSessionStoreTest {

    private Path tempDirectory; // 每个测试独占的 SQLite 目录

    @BeforeEach
    void createTestDirectory() {
        tempDirectory = Path.of(
                        "target", "sqlite-session-store-tests", UUID.randomUUID().toString())
                .toAbsolutePath();
    }

    @Test
    void createsFindableSessionAndPreservesTimes() {
        Instant createdAt = Instant.parse("2026-10-01T08:00:00.123Z");

        try (SqliteSessionStore store =
                new SqliteSessionStore(tempDirectory.resolve("round-trip.db"))) {
            store.create("session-1", "Planning", createdAt);

            SessionSummary expected =
                    new SessionSummary("session-1", "Planning", createdAt, null);
            assertEquals(expected, store.find("session-1").orElseThrow());
            assertEquals(List.of(expected), store.list());
        }
    }

    @Test
    void createsWorkspaceColumnInNewDatabase() throws Exception {
        Path database = tempDirectory.resolve("workspace-column.db");

        try (SqliteSessionStore store = new SqliteSessionStore(database)) {
            assertTrue(hasColumn(database, "sessions", "workspace"));
        }
    }

    @Test
    void migratesLegacySessionsTableWithoutWorkspaceColumn() throws Exception {
        Path database = tempDirectory.resolve("legacy.db");
        Instant createdAt = Instant.parse("2026-10-01T08:00:00Z");
        Files.createDirectories(tempDirectory);
        try (Connection connection = DriverManager.getConnection(sqliteUrl(database));
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE sessions (
                        id         TEXT PRIMARY KEY,
                        title      TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute(
                    "INSERT INTO sessions (id, title, created_at, updated_at) VALUES "
                            + "('legacy', 'Legacy row', "
                            + createdAt.toEpochMilli()
                            + ", "
                            + createdAt.toEpochMilli()
                            + ")");
        }

        try (SqliteSessionStore store = new SqliteSessionStore(database)) {
            assertTrue(hasColumn(database, "sessions", "workspace"));
            assertEquals(
                    new SessionSummary("legacy", "Legacy row", createdAt, null),
                    store.find("legacy").orElseThrow());
        }
    }

    @Test
    void listsByUpdatedTimeDescendingAndMovesTouchedSessionToFront() {
        Instant firstCreatedAt = Instant.parse("2026-10-01T08:00:00Z");
        Instant secondCreatedAt = Instant.parse("2026-10-02T08:00:00Z");

        try (SqliteSessionStore store =
                new SqliteSessionStore(tempDirectory.resolve("ordering.db"))) {
            store.create("first", "First", firstCreatedAt);
            store.create("second", "Second", secondCreatedAt);
            assertEquals(List.of("second", "first"), ids(store.list()));

            store.touch("first");

            assertEquals(List.of("first", "second"), ids(store.list()));
        }
    }

    @Test
    void renamesKnownSessionAndRejectsUnknownSession() {
        Instant createdAt = Instant.parse("2026-10-01T08:00:00Z");

        try (SqliteSessionStore store =
                new SqliteSessionStore(tempDirectory.resolve("rename.db"))) {
            store.create("session-1", "Before", createdAt);

            assertTrue(store.rename("session-1", "After"));
            assertEquals("After", store.find("session-1").orElseThrow().title());
            assertFalse(store.rename("missing", "After"));
        }
    }

    @Test
    void updatesWorkspaceAndRefreshesUpdatedAt() throws Exception {
        Path database = tempDirectory.resolve("workspace-update.db");
        Instant createdAt = Instant.parse("2026-10-01T08:00:00Z");

        try (SqliteSessionStore store = new SqliteSessionStore(database)) {
            store.create("session-1", "Workspace", createdAt);
            Instant oldUpdatedAt = readUpdatedAt(database, "session-1");

            assertTrue(store.updateWorkspace("session-1", "C:\\work"));
            assertFalse(store.updateWorkspace("missing", "C:\\work"));
            assertEquals(
                    "C:\\work",
                    store.find("session-1").orElseThrow().workspace());
            assertTrue(
                    readUpdatedAt(database, "session-1").toEpochMilli()
                            > oldUpdatedAt.toEpochMilli());
        }
    }

    @Test
    void fillsOnlyUnnamedSessionTitles() {
        Instant createdAt = Instant.parse("2026-10-01T08:00:00Z");

        try (SqliteSessionStore store =
                new SqliteSessionStore(tempDirectory.resolve("auto-name.db"))) {
            store.create("named", "Manual", createdAt);
            store.create("unnamed", null, createdAt);

            assertTrue(store.renameIfUnnamed("unnamed", "Generated"));
            assertFalse(store.renameIfUnnamed("named", "Replacement"));
            assertEquals("Generated", store.find("unnamed").orElseThrow().title());
            assertEquals("Manual", store.find("named").orElseThrow().title());
            assertFalse(store.renameIfUnnamed("missing", "Generated"));
        }
    }

    @Test
    void deletesSession() {
        try (SqliteSessionStore store =
                new SqliteSessionStore(tempDirectory.resolve("delete.db"))) {
            store.create("session-1", "Delete me", Instant.now());

            assertTrue(store.delete("session-1"));
            assertFalse(store.delete("session-1"));
            assertTrue(store.find("session-1").isEmpty());
        }
    }

    @Test
    void keepsSessionAfterClosingAndReopeningDatabase() {
        Path database = tempDirectory.resolve("restart.db");
        Instant createdAt = Instant.parse("2026-10-01T08:00:00Z");

        try (SqliteSessionStore store = new SqliteSessionStore(database)) {
            store.create("session-1", "Persistent", createdAt);
        }

        try (SqliteSessionStore reopened = new SqliteSessionStore(database)) {
            SessionSummary restored = reopened.find("session-1").orElseThrow();
            assertEquals("Persistent", restored.title());
            assertEquals(createdAt, restored.createdAt());
        }
    }

    @Test
    void backfillsSessionsThatHaveEventsButNoMetadata() {
        Path database = tempDirectory.resolve("backfill.db");
        Instant backfillTime = Instant.parse("2026-10-03T08:00:00Z");

        try (SqliteEventStore events = new SqliteEventStore(database);
                SqliteSessionStore sessions = new SqliteSessionStore(database)) {
            events.append("event-only", new SessionEvent.RunFinished(false));

            assertEquals(1, sessions.backfill(backfillTime));
            assertEquals(0, sessions.backfill(backfillTime));

            SessionSummary restored = sessions.find("event-only").orElseThrow();
            assertNull(restored.title());
            assertEquals(backfillTime, restored.createdAt());
        }
    }

    @Test
    void memoryDatabaseBackfillIsAnAcceptedNoOp() {
        Path memoryDatabase = mock(Path.class);
        when(memoryDatabase.toString()).thenReturn(":memory:");

        try (SqliteSessionStore store = new SqliteSessionStore(memoryDatabase)) {
            assertEquals(0, store.backfill(Instant.now()));
        }
    }

    private static List<String> ids(List<SessionSummary> sessions) {
        return sessions.stream().map(SessionSummary::id).toList();
    }

    private static boolean hasColumn(Path database, String table, String column)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(sqliteUrl(database));
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Instant readUpdatedAt(Path database, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(sqliteUrl(database));
                var statement = connection.prepareStatement(
                        "SELECT updated_at FROM sessions WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return Instant.ofEpochMilli(result.getLong("updated_at"));
            }
        }
    }

    private static String sqliteUrl(Path database) {
        return "jdbc:sqlite:" + database.toAbsolutePath().normalize()
                .toString()
                .replace('\\', '/');
    }
}
