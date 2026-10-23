package io.queueless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import io.queueless.server.service.*;
import io.queueless.server.web.DashboardBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import static io.queueless.server.integration.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QUEUELESS_TEST_DB_URL", matches = ".+")
class NotificationWorkflowTest {
    static HikariDataSource db;
    Fixture f;
    QueueService queue;
    @BeforeAll static void start() { db = database(); }
    @AfterAll static void stop() { if (db != null) db.close(); }
    @BeforeEach void setup() throws Exception {
        f = fixture(db); queue = new QueueService(db, new ObjectMapper(), Clock.systemUTC());
    }

    @Test void exactSnapshotBatchSupportsStaleRowsAndReplayWithoutReadingNewArrivals() throws Exception {
        long one = notification(f.user().id(), "Visit #12: join"), two = notification(f.user().id(), "Visit #12: cancel_visit");
        long foreign = notification(f.staff().id(), "Staff update");
        var query = new QueryService(db, Clock.systemUTC());
        var before = new DashboardBean(query.snapshot(f.user(), f.service()), new ObjectMapper(), f.service());
        assertEquals(2, before.getUnreadCount());
        var command = batch(before.getUnreadNotificationIds());
        queue.execute(f.user(), "another-tab", command("READ_NOTIFICATION", 0, one, 0, 0, 0, Map.of()));
        long arrival = notification(f.user().id(), "A new arrival after the snapshot");
        var result = queue.execute(f.user(), "browser", command);
        assertEquals("1 notification marked as read.", result.message());
        assertEquals(1, count(db, "SELECT is_read FROM notifications WHERE id=?", two));
        assertEquals(0, count(db, "SELECT is_read FROM notifications WHERE id=?", arrival));
        assertEquals(0, count(db, "SELECT is_read FROM notifications WHERE id=?", foreign));
        var replay = queue.execute(f.user(), "browser", command);
        assertTrue(replay.replayed()); assertEquals(result.message(), replay.message());
        assertEquals(1, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=? AND status='DONE'", f.user().id(), command.requestId()));
        assertEquals("Those notifications are already read.", queue.execute(f.user(), "browser", batch(one + "," + two)).message());
        var changedBody = new Api.Command(command.requestId(), command.operation(), 0, 0, 0, 0, 0, Map.of("notificationIds", Long.toString(arrival)));
        assertEquals("REQUEST_ID_REUSED", assertThrows(AppException.class, () -> queue.execute(f.user(), "browser", changedBody)).code());
        assertEquals(0, count(db, "SELECT is_read FROM notifications WHERE id=?", arrival));
    }

    @Test void foreignOrMissingIdRejectsEntireBatchAndRollsBackReceipt() throws Exception {
        long mine = notification(f.user().id(), "Mine"), foreign = notification(f.staff().id(), "Not mine");
        for (long unavailable : new long[]{foreign, Long.MAX_VALUE}) {
            var command = batch(mine + "," + unavailable);
            var error = assertThrows(AppException.class, () -> queue.execute(f.user(), "browser", command));
            assertEquals(409, error.status());
            assertEquals(0, count(db, "SELECT is_read FROM notifications WHERE id=?", mine));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?", f.user().id(), command.requestId()));
        }
        assertEquals(0, count(db, "SELECT is_read FROM notifications WHERE id=?", foreign));
    }

    @Test void simultaneousDuplicateBatchesCommitOnceAndReplayTheSameCount() throws Exception {
        long one = notification(f.user().id(), "One"), two = notification(f.user().id(), "Two");
        var command = batch(one + "," + two);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Api.Result> request = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return queue.execute(f.user(), "browser", command);
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Api.Result::replayed).count());
            assertTrue(results.stream().allMatch(result -> result.message().equals("2 notifications marked as read.")));
        }
        assertEquals(2, count(db, "SELECT SUM(is_read) FROM notifications WHERE id IN (?,?)", one, two));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=? AND status='DONE'", f.user().id(), command.requestId()));
    }

    @Test void olderIdCommittedAfterSnapshotStaysUnreadDuringConcurrentBulkCommand() throws Exception {
        long olderWork = work(f.user().id(), "Late delivery");
        try (var late = db.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            late.setAutoCommit(false);
            long older = Sql.insert(late, "INSERT INTO notifications(user_id,work_id,message) VALUES(?,?,'Late delivery')", f.user().id(), olderWork);
            long visible = notification(f.user().id(), "Visible delivery");
            assertTrue(older < visible);
            var snapshot = new DashboardBean(new QueryService(db, Clock.systemUTC()).snapshot(f.user(), f.service()), new ObjectMapper(), f.service());
            assertEquals(Long.toString(visible), snapshot.getUnreadNotificationIds());
            var attemptedUserLock = new CountDownLatch(1);
            DataSource observed = wrap(db, (connection, method, args) -> {
                Object returned = invoke(method, connection, args);
                if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("SELECT role FROM users")) {
                    var statement = (PreparedStatement) returned;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        if (m.getName().equals("executeQuery")) attemptedUserLock.countDown();
                        return invoke(m, statement, a);
                    });
                }
                return returned;
            });
            var competingQueue = new QueueService(observed, new ObjectMapper(), Clock.systemUTC());
            var pending = executor.submit(() -> competingQueue.execute(f.user(), "browser", batch(snapshot.getUnreadNotificationIds())));
            assertTrue(attemptedUserLock.await(10, TimeUnit.SECONDS));
            late.commit();
            assertEquals("1 notification marked as read.", pending.get(10, TimeUnit.SECONDS).message());
            assertEquals(0, count(db, "SELECT is_read FROM notifications WHERE id=?", older));
            assertEquals(1, count(db, "SELECT is_read FROM notifications WHERE id=?", visible));
        }
    }

    @Test void databaseFailureAfterUpdatesRollsBackEveryReadFlagAndReceiptThenSameRequestRecovers() throws Exception {
        long one = notification(f.user().id(), "One"), two = notification(f.user().id(), "Two");
        var command = batch(one + "," + two);
        DataSource failing = wrap(db, (connection, method, args) -> {
            Object returned = invoke(method, connection, args);
            if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("UPDATE command_receipts SET status='DONE'"))
                throw new SQLNonTransientException("Injected failure after notification updates");
            return returned;
        });
        assertThrows(SQLException.class, () -> new QueueService(failing, new ObjectMapper(), Clock.systemUTC()).execute(f.user(), "browser", command));
        assertEquals(0, count(db, "SELECT SUM(is_read) FROM notifications WHERE id IN (?,?)", one, two));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?", f.user().id(), command.requestId()));
        assertEquals("2 notifications marked as read.", queue.execute(f.user(), "browser", command).message());
        assertEquals(2, count(db, "SELECT SUM(is_read) FROM notifications WHERE id IN (?,?)", one, two));
    }

    @Test void malformedOrOversizedBatchesAreRejectedAndPresentationCapsOnlyBulkInput() throws Exception {
        for (String input : List.of("", "0", "-1", "1,1", "1,", "1, 2", "9223372036854775808", LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(",")))) {
            var command = batch(input);
            assertEquals(400, assertThrows(AppException.class, () -> queue.execute(f.user(), "browser", command)).status());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?", f.user().id(), command.requestId()));
        }
        for (int i = 0; i < 101; i++) notification(f.user().id(), "Update " + i);
        var bean = new DashboardBean(new QueryService(db, Clock.systemUTC()).snapshot(f.user(), f.service()), new ObjectMapper(), f.service());
        assertEquals(101, bean.getUnreadCount()); assertEquals(101, bean.getNotifications().size());
        assertEquals(100, bean.getBulkNotificationCount()); assertEquals(100, bean.getUnreadNotificationIds().split(",").length);
        assertEquals("100 notifications marked as read.", queue.execute(f.user(), "browser", batch(bean.getUnreadNotificationIds())).message());
        assertEquals(1, count(db, "SELECT COUNT(*) FROM notifications WHERE user_id=? AND is_read=FALSE", f.user().id()));
    }

    @Test void snapshotProjectsLegacyRowsForBothClientsWithoutChangingStoredPayloads() throws Exception {
        long joined = notification(f.user().id(), "Visit #12: join"), cancelled = notification(f.user().id(), "Visit #12: cancel_visit");
        long unknown = notification(f.user().id(), "<b>Custom text</b>");
        var snapshot = new QueryService(db, Clock.systemUTC()).snapshot(f.user(), f.service());
        var byId = snapshot.notifications().stream().collect(Collectors.toMap(Api.Notification::id, n -> n));
        assertEquals("Visit #12 joined the queue.", byId.get(joined).message());
        assertEquals(Api.NotificationKind.QUEUED, byId.get(joined).kind());
        assertEquals("Visit #12 was cancelled.", byId.get(cancelled).message());
        assertEquals(Api.NotificationKind.CANCELLED, byId.get(cancelled).kind());
        assertEquals("<b>Custom text</b>", byId.get(unknown).message());
        try (var c = db.getConnection()) {
            assertEquals("Visit #12: cancel_visit", Sql.one(c, "SELECT message FROM notifications WHERE id=?", r -> r.getString(1), cancelled).orElseThrow());
        }
    }

    private Api.Command batch(String ids) { return command("READ_NOTIFICATIONS", 0, 0, 0, 0, 0, Map.of("notificationIds", ids)); }
    private long work(long user, String message) throws SQLException {
        try (var c = db.getConnection()) { return Sql.insert(c, "INSERT INTO notification_work(user_id,event_key,message,done) VALUES(?,?,?,TRUE)", user, "test:" + UUID.randomUUID(), message); }
    }
    private long notification(long user, String message) throws SQLException {
        long work = work(user, message);
        try (var c = db.getConnection()) { return Sql.insert(c, "INSERT INTO notifications(user_id,work_id,message) VALUES(?,?,?)", user, work, message); }
    }
    @FunctionalInterface private interface ConnectionCall { Object call(Connection connection, Method method, Object[] args) throws Throwable; }
    private DataSource wrap(DataSource source, ConnectionCall call) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            Object returned = invoke(method, source, args);
            if (returned instanceof Connection connection)
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> call.call(connection, m, a));
            return returned;
        });
    }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
