package io.queueless.server.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.queueless.contracts.Api;
import io.queueless.server.service.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A receipt and its business effects commit together. PENDING is never committed.
 * Callbacks may run again after rollback and must not perform external side effects.
 */
public final class Commands {
    private static final Logger LOG = LoggerFactory.getLogger(Commands.class);
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public Commands(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.mapper = Objects.requireNonNull(mapper).copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /**
     * lockAndAuthorize must acquire existing services, then required users, in sorted ID order.
     * It must only lock/read and check current authority: state/version/lease preconditions belong
     * in work, since they have already been consumed when an earlier result is replayed.
     * Every DAO in both callbacks must use the supplied connection without committing it.
     */
    public Api.Result execute(long actor, Api.Command command, Sql.Work<Void> lockAndAuthorize,
                              Sql.Work<Api.Result> work) throws SQLException {
        if (actor <= 0) {
            throw new AppException(401, "AUTHENTICATION_REQUIRED", "Please sign in to continue.");
        }
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(lockAndAuthorize, "lockAndAuthorize");
        Objects.requireNonNull(work, "work");
        String requestId = normalizeRequestId(command.requestId());
        if (command.operation() == null || command.operation().isBlank()) {
            throw new AppException(400, "INVALID_COMMAND", "A command operation is required.");
        }
        String fingerprint = fingerprint(mapper, command);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                Attempt outcome = attempt(actor, requestId, fingerprint, lockAndAuthorize, work);
                if (outcome.result() != null) {
                    return outcome.result();
                }
                Api.Result replay = replay(actor, requestId, fingerprint, lockAndAuthorize);
                if (replay != null) {
                    return replay;
                }
                // A disappearing receipt is not proof of failure: arbitrate with another INSERT.
            } catch (SQLException failure) {
                if (!retryable(failure)) {
                    throw failure;
                }
                LOG.warn("Retryable command database failure: attempt {}, SQLState {}, vendor code {}",
                        attempt + 1, failure.getSQLState(), failure.getErrorCode());
            }
            if (attempt + 1 < MAX_ATTEMPTS) {
                backoff(attempt);
            }
        }
        throw new AppException(503, "RETRY_SAME_REQUEST",
                "The command outcome could not be confirmed. Retry with the same request ID.");
    }

    private Attempt attempt(long actor, String requestId, String fingerprint,
                            Sql.Work<Void> lockAndAuthorize, Sql.Work<Api.Result> work) throws SQLException {
        boolean commitAttempted = false;
        try (Connection connection = dataSource.getConnection()) {
            begin(connection);
            try {
                lockAndAuthorize.run(connection);
                try {
                    Sql.update(connection,
                            "INSERT INTO command_receipts(actor_id,request_id,fingerprint,status) VALUES (?,?,?,'PENDING')",
                            actor, requestId, fingerprint);
                } catch (SQLException failure) {
                    if (!duplicateReceipt(failure)) {
                        throw failure;
                    }
                    connection.rollback();
                    return new Attempt(null);
                }
                Api.Result result = Objects.requireNonNull(work.run(connection), "Command result");
                result = new Api.Result(result.entityId(), result.version(), result.message(), false);
                String encoded = encode(result);
                int changed = Sql.update(connection,
                        "UPDATE command_receipts SET status='DONE',result_json=?,completed_at=CURRENT_TIMESTAMP(6) "
                                + "WHERE actor_id=? AND request_id=? AND status='PENDING'",
                        encoded, actor, requestId);
                if (changed != 1) {
                    throw new SQLNonTransientException("Command receipt was not finalized exactly once");
                }
                commitAttempted = true;
                connection.commit();
                return new Attempt(result);
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            if (commitAttempted) {
                throw new UncertainCommit(failure);
            }
            throw failure;
        }
    }

    private Api.Result replay(long actor, String requestId, String fingerprint,
                              Sql.Work<Void> lockAndAuthorize) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            begin(connection);
            try {
                lockAndAuthorize.run(connection);
                Receipt receipt = Sql.one(connection,
                        "SELECT fingerprint,status,result_json FROM command_receipts WHERE actor_id=? AND request_id=?",
                        row -> new Receipt(row.getString("fingerprint"), row.getString("status"),
                                row.getString("result_json")), actor, requestId).orElse(null);
                if (receipt == null) {
                    connection.rollback();
                    return null;
                }
                if (!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
                        receipt.fingerprint().getBytes(StandardCharsets.US_ASCII))) {
                    throw new AppException(409, "REQUEST_ID_REUSED",
                            "This request ID was already used for a different command.");
                }
                if (!"DONE".equals(receipt.status()) || receipt.resultJson() == null) {
                    throw new SQLNonTransientException("A committed command receipt is incomplete");
                }
                Api.Result stored = decode(receipt.resultJson());
                // This transaction acquired authorization locks but made no changes.
                connection.rollback();
                return new Api.Result(stored.entityId(), stored.version(), stored.message(), true);
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    static String normalizeRequestId(String requestId) {
        try {
            UUID parsed = UUID.fromString(requestId);
            String normalized = parsed.toString();
            if (!normalized.equalsIgnoreCase(requestId)) {
                throw new IllegalArgumentException("Noncanonical UUID");
            }
            return normalized;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new AppException(400, "INVALID_REQUEST_ID", "The request ID must be a UUID.");
        }
    }

    static String fingerprint(ObjectMapper mapper, Api.Command command) throws SQLException {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("operation", command.operation());
        canonical.put("serviceId", command.serviceId());
        canonical.put("entityId", command.entityId());
        canonical.put("counterId", command.counterId());
        canonical.put("expectedVersion", command.expectedVersion());
        canonical.put("claimVersion", command.claimVersion());
        canonical.put("fields", new TreeMap<>(command.fields()));
        try {
            byte[] bytes = mapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException failure) {
            throw new SQLNonTransientException("Could not encode the command fingerprint", failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    static boolean duplicateReceipt(SQLException failure) {
        return failure.getErrorCode() == 1062 && "23000".equals(failure.getSQLState());
    }

    static boolean retryable(SQLException failure) {
        if (failure instanceof UncertainCommit || failure instanceof SQLRecoverableException
                || failure instanceof SQLTransientConnectionException || failure instanceof SQLTimeoutException
                || failure instanceof SQLTransactionRollbackException) {
            return true;
        }
        String state = failure.getSQLState();
        return failure.getErrorCode() == 1213 || failure.getErrorCode() == 1205
                || "40001".equals(state) || (state != null && state.startsWith("08"));
    }

    private String encode(Api.Result result) throws SQLException {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException failure) {
            throw new SQLNonTransientException("Could not encode the command result", failure);
        }
    }

    private Api.Result decode(String json) throws SQLException {
        try {
            Api.Result result = mapper.readValue(json, Api.Result.class);
            if (result == null) {
                throw new SQLNonTransientException("Stored command result is missing");
            }
            return result;
        } catch (JsonProcessingException failure) {
            throw new SQLNonTransientException("Stored command result is invalid", failure);
        }
    }

    private static void begin(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep((25L << attempt) + ThreadLocalRandom.current().nextLong(25L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AppException(503, "RETRY_SAME_REQUEST",
                    "The request was interrupted. Retry with the same request ID.");
        }
    }

    private record Attempt(Api.Result result) { }
    private record Receipt(String fingerprint, String status, String resultJson) { }

    private static final class UncertainCommit extends SQLException {
        private UncertainCommit(SQLException cause) {
            super("Commit outcome requires receipt resolution", cause.getSQLState(), cause.getErrorCode(), cause);
        }
    }
}
