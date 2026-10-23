package io.queueless.server.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.contracts.Api;
import io.queueless.server.service.AppException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real InnoDB tests. Every fixture is unique; this suite never clears any tables. */
@EnabledIfEnvironmentVariable(named = "QUEUELESS_TEST_DB_URL", matches = ".+")
@Timeout(30)
class CommandsDatabaseTest {
    private static HikariDataSource database;
    // Deliberately no parent mutex: the contention tests must exercise the unique INSERT itself.
    private static final Sql.Work<Void> NO_AUTH_LOCK = connection -> null;
    private final List<Fixture> ownedFixtures = new ArrayList<>();
    private Fixture fixture;
    private Commands commands;

    @BeforeAll
    static void startDatabase() throws SQLException {
        String url = System.getenv("QUEUELESS_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/queueless_test(?:\\?.*)?")) {
            throw new IllegalStateException("Receipt integration tests require the queueless_test database");
        }
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(url);
        config.setUsername(System.getenv("QUEUELESS_DB_USER"));
        config.setPassword(System.getenv("QUEUELESS_DB_PASSWORD"));
        config.setMaximumPoolSize(6);
        config.setMinimumIdle(1);
        config.setPoolName("ReceiptIntegrationTests");
        config.setConnectionTimeout(5_000);
        config.setConnectionInitSql("SET time_zone = '+00:00', innodb_lock_wait_timeout = 2");
        database = new HikariDataSource(config);
        try (Connection connection = database.getConnection()) {
            assertEquals("queueless_test", Sql.one(connection, "SELECT DATABASE()", row -> row.getString(1)).orElseThrow());
        }
        Database.migrate(database);
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void createFixture() throws SQLException {
        fixture = newFixture();
        commands = new Commands(database, new ObjectMapper());
    }

    @AfterEach
    void noPendingReceiptWasCommitted() throws SQLException {
        for (Fixture owned : ownedFixtures) {
            assertEquals(0, count("SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND status='PENDING'", owned.actor()));
        }
    }

    @Test
    void concurrentAbsentReceiptInsertsProduceOneEffectAndOneReplay() throws Exception {
        Api.Command command = command(fixture);
        AtomicInteger workCalls = new AtomicInteger();
        CountDownLatch firstWorkStarted = new CountDownLatch(1);
        CountDownLatch bothInsertsEntered = new CountDownLatch(2);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        Commands contended = new Commands(observeReceiptInserts(bothInsertsEntered), new ObjectMapper());
        assertEquals(0, receiptCount(fixture, command));
        Sql.Work<Api.Result> work = connection -> {
            workCalls.incrementAndGet();
            firstWorkStarted.countDown();
            await(releaseWinner);
            return applyEffects(connection, fixture, command);
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(() -> contended.execute(fixture.actor(), command, NO_AUTH_LOCK, work));
                await(firstWorkStarted);
                var second = executor.submit(() -> contended.execute(fixture.actor(), command, NO_AUTH_LOCK, work));
                await(bothInsertsEntered);
                assertEquals(1, workCalls.get(), "The loser must not enter business work");
                assertEquals(0, receiptCount(fixture, command), "An uncommitted PENDING receipt is not visible");
                releaseWinner.countDown();
                Api.Result original = first.get(10, TimeUnit.SECONDS);
                Api.Result replay = second.get(10, TimeUnit.SECONDS);
                assertFalse(original.replayed());
                assertTrue(replay.replayed());
                assertEquals(original.entityId(), replay.entityId());
                assertEquals(original.version(), replay.version());
                assertEquals(original.message(), replay.message());
                assertEquals(1, workCalls.get());
                assertEffects(fixture, command, 1);
            } finally {
                releaseWinner.countDown();
            }
        }
    }

    @Test
    void businessFailureRollsBackReceiptRevisionAuditAndNotificationWork() throws Exception {
        Api.Command command = command(fixture);
        AppException failure = assertThrows(AppException.class, () -> commands.execute(fixture.actor(), command,
                NO_AUTH_LOCK, connection -> {
                    applyEffects(connection, fixture, command);
                    throw new AppException(409, "INJECTED_FAILURE", "The test rejects this transaction.");
                }));
        assertEquals("INJECTED_FAILURE", failure.code());
        assertEffects(fixture, command, 0);
        Api.Result retried = commands.execute(fixture.actor(), command, NO_AUTH_LOCK,
                connection -> applyEffects(connection, fixture, command));
        assertFalse(retried.replayed());
        assertEffects(fixture, command, 1);
    }

    @Test
    void receiptFingerprintConflictDoesNotRunBusinessWorkAgain() throws Exception {
        Api.Command original = command(fixture);
        AtomicInteger workCalls = new AtomicInteger();
        Sql.Work<Api.Result> work = connection -> {
            workCalls.incrementAndGet();
            return applyEffects(connection, fixture, original);
        };
        commands.execute(fixture.actor(), original, NO_AUTH_LOCK, work);
        Api.Command changed = new Api.Command(original.requestId(), original.operation(), original.serviceId(),
                original.entityId(), original.counterId(), original.expectedVersion(), original.claimVersion(),
                Map.of("reason", "different command input"));
        AppException failure = assertThrows(AppException.class,
                () -> commands.execute(fixture.actor(), changed, NO_AUTH_LOCK, work));
        assertEquals(409, failure.status());
        assertEquals("REQUEST_ID_REUSED", failure.code());
        assertEquals(1, workCalls.get());
        assertEffects(fixture, original, 1);
    }

    @Test
    void receiptReplayRechecksCurrentAuthorization() throws Exception {
        Api.Command command = command(fixture);
        AtomicInteger workCalls = new AtomicInteger();
        Sql.Work<Void> authorize = connection -> {
            boolean active = Sql.one(connection, "SELECT active FROM users WHERE id=?",
                    row -> row.getBoolean(1), fixture.actor()).orElse(false);
            if (!active) {
                throw new AppException(403, "ACCOUNT_DISABLED", "This account is disabled.");
            }
            return null;
        };
        Sql.Work<Api.Result> work = connection -> {
            workCalls.incrementAndGet();
            return applyEffects(connection, fixture, command);
        };
        commands.execute(fixture.actor(), command, authorize, work);
        try (Connection connection = database.getConnection()) {
            Sql.update(connection, "UPDATE users SET active=FALSE WHERE id=?", fixture.actor());
        }
        AppException failure = assertThrows(AppException.class,
                () -> commands.execute(fixture.actor(), command, authorize, work));
        assertEquals(403, failure.status());
        assertEquals(1, workCalls.get());
        assertEffects(fixture, command, 1);
    }

    @Test
    void replayReturnsTheOriginalResultAfterDomainStateHasAdvanced() throws Exception {
        Api.Command command = command(fixture);
        Api.Result original = commands.execute(fixture.actor(), command, NO_AUTH_LOCK,
                connection -> applyEffects(connection, fixture, command));
        try (Connection connection = database.getConnection()) {
            Sql.update(connection, "UPDATE service_runtime SET revision=revision+10 WHERE service_id=?", fixture.service());
        }
        Api.Result replay = commands.execute(fixture.actor(), command, NO_AUTH_LOCK, connection -> {
            throw new AssertionError("Replaying an old command must not rerun consumed business preconditions");
        });
        assertTrue(replay.replayed());
        assertEquals(original.version(), replay.version());
        assertEquals(11, count("SELECT revision FROM service_runtime WHERE service_id=?", fixture.service()));
        assertEquals(1, receiptCount(fixture, command));
    }

    @Test
    void lostCommitAcknowledgmentResolvesTheReceiptWithoutAnotherMutation() throws Exception {
        Api.Command command = command(fixture);
        AtomicInteger workCalls = new AtomicInteger();
        AtomicBoolean loseFirstCommit = new AtomicBoolean(true);
        DataSource lossy = decorateConnections(connection -> proxyConnection(connection, (method, arguments) -> {
            if (method.getName().equals("commit") && loseFirstCommit.compareAndSet(true, false)) {
                invoke(connection, method, arguments);
                throw new SQLRecoverableException("Injected loss of commit acknowledgment", "08006");
            }
            return invoke(connection, method, arguments);
        }));
        Api.Result result = new Commands(lossy, new ObjectMapper()).execute(fixture.actor(), command,
                NO_AUTH_LOCK, connection -> {
                    workCalls.incrementAndGet();
                    return applyEffects(connection, fixture, command);
                });
        assertFalse(loseFirstCommit.get());
        assertTrue(result.replayed());
        assertEquals(1, workCalls.get());
        assertEffects(fixture, command, 1);
    }

    @Test
    void failureBeforeCommitIsRetriedWithoutKeepingPartialEffects() throws Exception {
        Api.Command command = command(fixture);
        AtomicInteger workCalls = new AtomicInteger();
        AtomicBoolean loseFirstCommit = new AtomicBoolean(true);
        DataSource lossy = decorateConnections(connection -> proxyConnection(connection, (method, arguments) -> {
            if (method.getName().equals("commit") && loseFirstCommit.compareAndSet(true, false)) {
                throw new SQLRecoverableException("Injected failure before commit reached MySQL", "08006");
            }
            return invoke(connection, method, arguments);
        }));
        Api.Result result = new Commands(lossy, new ObjectMapper()).execute(fixture.actor(), command,
                NO_AUTH_LOCK, connection -> {
                    workCalls.incrementAndGet();
                    return applyEffects(connection, fixture, command);
                });
        assertFalse(result.replayed());
        assertEquals(2, workCalls.get());
        assertEffects(fixture, command, 1);
    }

    @Test
    void realLockTimeoutRollsBackTheWholeAttemptBeforeRetry() throws Exception {
        Fixture blocked = newFixture();
        Api.Command command = command(fixture);
        CountDownLatch timeoutObserved = new CountDownLatch(1);
        CountDownLatch blockerReleased = new CountDownLatch(1);
        AtomicReference<SQLException> timeout = new AtomicReference<>();
        AtomicInteger workCalls = new AtomicInteger();
        try (Connection blocker = database.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            Sql.one(blocker, "SELECT revision FROM service_runtime WHERE service_id=? FOR UPDATE",
                    row -> row.getLong(1), blocked.service()).orElseThrow();
            try {
                var result = executor.submit(() -> commands.execute(fixture.actor(), command, NO_AUTH_LOCK, connection -> {
                    workCalls.incrementAndGet();
                    Api.Result applied = applyEffects(connection, fixture, command);
                    try {
                        Sql.update(connection, "UPDATE service_runtime SET revision=revision+1 WHERE service_id=?", blocked.service());
                    } catch (SQLException failure) {
                        timeout.set(failure);
                        timeoutObserved.countDown();
                        await(blockerReleased);
                        throw failure;
                    }
                    return applied;
                }));
                await(timeoutObserved);
                assertNotNull(timeout.get());
                assertEquals(1205, timeout.get().getErrorCode());
                blocker.rollback();
                blockerReleased.countDown();
                assertFalse(result.get(10, TimeUnit.SECONDS).replayed());
                assertEquals(2, workCalls.get());
                assertEffects(fixture, command, 1);
                assertEquals(1, count("SELECT revision FROM service_runtime WHERE service_id=?", blocked.service()));
            } finally {
                blocker.rollback();
                blockerReleased.countDown();
            }
        }
    }

    @Test
    void realDeadlockVictimRetriesItsWholeTransaction() throws Exception {
        Fixture other = newFixture();
        Api.Command firstCommand = command(fixture);
        Api.Command secondCommand = command(other);
        CountDownLatch firstAttemptsHaveWritten = new CountDownLatch(2);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> commands.execute(fixture.actor(), firstCommand, NO_AUTH_LOCK,
                    deadlockingWork(fixture, other, firstCommand, firstCalls, deadlocks, firstAttemptsHaveWritten)));
            var second = executor.submit(() -> commands.execute(other.actor(), secondCommand, NO_AUTH_LOCK,
                    deadlockingWork(other, fixture, secondCommand, secondCalls, deadlocks, firstAttemptsHaveWritten)));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertTrue(deadlocks.get() >= 1, "A real InnoDB deadlock must have been observed");
        assertTrue(firstCalls.get() + secondCalls.get() >= 3);
        assertEquals(2, count("SELECT revision FROM service_runtime WHERE service_id=?", fixture.service()));
        assertEquals(2, count("SELECT revision FROM service_runtime WHERE service_id=?", other.service()));
        assertEquals(1, receiptCount(fixture, firstCommand));
        assertEquals(1, receiptCount(other, secondCommand));
        assertEquals(1, eventCount(fixture, firstCommand));
        assertEquals(1, eventCount(other, secondCommand));
        assertEquals(1, notificationWorkCount(firstCommand));
        assertEquals(1, notificationWorkCount(secondCommand));
    }

    @Test
    void domainUniqueViolationIsNotMistakenForAReceiptReplay() throws Exception {
        Api.Command command = command(fixture);
        AtomicInteger workCalls = new AtomicInteger();
        SQLException failure = assertThrows(SQLException.class, () -> commands.execute(fixture.actor(), command,
                NO_AUTH_LOCK, connection -> {
                    workCalls.incrementAndGet();
                    Api.Result applied = applyEffects(connection, fixture, command);
                    Sql.insert(connection, "INSERT INTO services(name,description) VALUES(?,'duplicate test')", fixture.name());
                    return applied;
                }));
        assertEquals(1062, failure.getErrorCode());
        assertEquals(1, workCalls.get());
        assertEffects(fixture, command, 0);
    }

    private Sql.Work<Api.Result> deadlockingWork(Fixture own, Fixture other, Api.Command command,
                                                AtomicInteger calls, AtomicInteger deadlocks,
                                                CountDownLatch firstAttemptsHaveWritten) {
        return connection -> {
            int attempt = calls.incrementAndGet();
            Api.Result applied = applyEffects(connection, own, command);
            if (attempt == 1) {
                firstAttemptsHaveWritten.countDown();
                await(firstAttemptsHaveWritten);
            }
            try {
                // Reverse order is deliberate test fault injection, never the production lock order.
                Sql.update(connection, "UPDATE service_runtime SET revision=revision+1 WHERE service_id=?", other.service());
            } catch (SQLException failure) {
                if (failure.getErrorCode() == 1213) {
                    deadlocks.incrementAndGet();
                }
                throw failure;
            }
            return applied;
        };
    }

    private Fixture newFixture() throws SQLException {
        try (Connection connection = database.getConnection()) {
            String unique = UUID.randomUUID().toString();
            long actor = Sql.insert(connection,
                    "INSERT INTO users(name,email,password_hash,role) VALUES('Receipt fixture',?,'not-a-login-hash','USER')",
                    unique + "@receipt.example.test");
            String name = "receipt-" + unique;
            long service = Sql.insert(connection, "INSERT INTO services(name,description) VALUES(?,'Receipt integration fixture')", name);
            Sql.update(connection, "INSERT INTO service_runtime(service_id) VALUES(?)", service);
            Fixture created = new Fixture(actor, service, name);
            ownedFixtures.add(created);
            return created;
        }
    }

    private static Api.Command command(Fixture target) {
        return new Api.Command(UUID.randomUUID().toString(), "RECEIPT_TEST", target.service(), 0, 0, 0, 0, Map.of());
    }

    private static Api.Result applyEffects(Connection connection, Fixture target, Api.Command command) throws SQLException {
        Sql.update(connection, "UPDATE service_runtime SET revision=revision+1 WHERE service_id=?", target.service());
        Sql.insert(connection,
                "INSERT INTO audit_events(actor_id,service_id,action,entity_id,detail) VALUES(?,?,'RECEIPT_TEST',?,?)",
                target.actor(), target.service(), target.service(), command.requestId());
        Sql.update(connection, "INSERT INTO notification_work(user_id,event_key,message) VALUES(?,?,'Receipt integration event')",
                target.actor(), "receipt:" + command.requestId());
        long revision = Sql.one(connection, "SELECT revision FROM service_runtime WHERE service_id=?",
                row -> row.getLong(1), target.service()).orElseThrow();
        return new Api.Result(target.service(), revision, "Receipt test applied", false);
    }

    private static void assertEffects(Fixture target, Api.Command command, long expected) throws SQLException {
        assertEquals(expected, count("SELECT revision FROM service_runtime WHERE service_id=?", target.service()));
        assertEquals(expected, receiptCount(target, command));
        assertEquals(expected, eventCount(target, command));
        assertEquals(expected, notificationWorkCount(command));
        assertEquals(expected, count("SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=? "
                + "AND status='DONE' AND completed_at IS NOT NULL AND result_json IS NOT NULL", target.actor(), command.requestId()));
    }

    private static long receiptCount(Fixture target, Api.Command command) throws SQLException {
        return count("SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?", target.actor(), command.requestId());
    }

    private static long eventCount(Fixture target, Api.Command command) throws SQLException {
        return count("SELECT COUNT(*) FROM audit_events WHERE actor_id=? AND service_id=? AND action='RECEIPT_TEST' AND detail=?",
                target.actor(), target.service(), command.requestId());
    }

    private static long notificationWorkCount(Api.Command command) throws SQLException {
        return count("SELECT COUNT(*) FROM notification_work WHERE event_key=?", "receipt:" + command.requestId());
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection connection = database.getConnection()) {
            return Sql.one(connection, sql, row -> row.getLong(1), arguments).orElseThrow();
        }
    }

    private static void await(CountDownLatch latch) throws SQLException {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) {
                throw new SQLException("Timed out coordinating integration-test transactions");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while coordinating integration-test transactions", interrupted);
        }
    }

    private static DataSource observeReceiptInserts(CountDownLatch entered) {
        return decorateConnections(connection -> proxyConnection(connection, (method, arguments) -> {
            Object result = invoke(connection, method, arguments);
            if (method.getName().equals("prepareStatement") && arguments != null && arguments.length > 0
                    && arguments[0] instanceof String sql && sql.startsWith("INSERT INTO command_receipts")
                    && result instanceof PreparedStatement statement) {
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                        (proxy, call, parameters) -> {
                            if (call.getName().equals("executeUpdate")) {
                                entered.countDown();
                            }
                            return invoke(statement, call, parameters);
                        });
            }
            return result;
        }));
    }

    private static DataSource decorateConnections(ConnectionDecorator decorator) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(database, method, arguments);
                    return result instanceof Connection connection ? decorator.decorate(connection) : result;
                });
    }

    private static Connection proxyConnection(Connection connection, JdbcInvocation invocation) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> invocation.call(method, arguments));
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @FunctionalInterface
    private interface ConnectionDecorator {
        Connection decorate(Connection connection);
    }

    @FunctionalInterface
    private interface JdbcInvocation {
        Object call(Method method, Object[] arguments) throws Throwable;
    }

    private record Fixture(long actor, long service, String name) { }
}
