package io.queueless.server.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queueless.contracts.Api;
import io.queueless.server.service.AppException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fingerprintIgnoresMapOrderAndRequestIdButKeepsCommandMeaning() throws SQLException {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("reason", "customer request");
        first.put("name", "Student Affairs");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("name", "Student Affairs");
        second.put("reason", "customer request");
        Api.Command original = command("CANCEL", 2, first);
        Api.Command reordered = new Api.Command("19c558d6-198b-4367-8b5d-a5038c5d90cc", "CANCEL",
                1, 4, 3, 2, 5, second);

        assertEquals(Commands.fingerprint(mapper, original), Commands.fingerprint(mapper, reordered));
        assertNotEquals(Commands.fingerprint(mapper, original),
                Commands.fingerprint(mapper, command("COMPLETE", 2, first)));
        assertNotEquals(Commands.fingerprint(mapper, original),
                Commands.fingerprint(mapper, command("CANCEL", 3, first)));
        assertNotEquals(Commands.fingerprint(mapper, original),
                Commands.fingerprint(mapper, command("CANCEL", 2, Map.of("reason", "different"))));
    }

    @Test
    void fingerprintIncludesEveryTargetAndClaimVersion() throws SQLException {
        Api.Command original = command("START", 2, Map.of());
        String expected = Commands.fingerprint(mapper, original);
        assertNotEquals(expected, Commands.fingerprint(mapper,
                new Api.Command(original.requestId(), "START", 9, 4, 3, 2, 5, Map.of())));
        assertNotEquals(expected, Commands.fingerprint(mapper,
                new Api.Command(original.requestId(), "START", 1, 9, 3, 2, 5, Map.of())));
        assertNotEquals(expected, Commands.fingerprint(mapper,
                new Api.Command(original.requestId(), "START", 1, 4, 9, 2, 5, Map.of())));
        assertNotEquals(expected, Commands.fingerprint(mapper,
                new Api.Command(original.requestId(), "START", 1, 4, 3, 2, 9, Map.of())));
    }

    @Test
    void normalizesUuidCaseButRejectsAbbreviatedOrPaddedKeys() {
        assertEquals("08ec875a-682c-4b50-bc0b-53abef5887a0",
                Commands.normalizeRequestId("08EC875A-682C-4B50-BC0B-53ABEF5887A0"));
        assertThrows(AppException.class, () -> Commands.normalizeRequestId("1-1-1-1-1"));
        assertThrows(AppException.class, () -> Commands.normalizeRequestId(" 08ec875a-682c-4b50-bc0b-53abef5887a0"));
        assertThrows(AppException.class, () -> Commands.normalizeRequestId(null));
    }

    @Test
    void retriesTransactionAndConnectionFailuresButNotIntegrityOrSyntaxErrors() {
        assertTrue(Commands.retryable(new SQLException("deadlock", "40001", 1213)));
        assertTrue(Commands.retryable(new SQLException("lock timeout", "HY000", 1205)));
        assertTrue(Commands.retryable(new SQLException("lost connection", "08S01", 0)));
        assertTrue(Commands.retryable(new SQLRecoverableException("connection broken")));
        assertFalse(Commands.retryable(new SQLException("duplicate", "23000", 1062)));
        assertFalse(Commands.retryable(new SQLException("bad syntax", "42000", 1064)));
        assertFalse(Commands.retryable(new SQLException("unknown failure")));
    }

    @Test
    void recognizesOnlyMysqlDuplicateKeyErrorsForReceiptArbitration() {
        assertTrue(Commands.duplicateReceipt(new SQLException("duplicate", "23000", 1062)));
        assertFalse(Commands.duplicateReceipt(new SQLException("foreign key", "23000", 1452)));
        assertFalse(Commands.duplicateReceipt(new SQLException("deadlock", "40001", 1213)));
    }

    private static Api.Command command(String operation, long version, Map<String, String> fields) {
        return new Api.Command("08ec875a-682c-4b50-bc0b-53abef5887a0", operation, 1, 4, 3, version, 5, fields);
    }
}
