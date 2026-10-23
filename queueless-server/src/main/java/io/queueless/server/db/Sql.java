package io.queueless.server.db;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLDataException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC operations use their caller's connection and never own a transaction. */
public final class Sql {
    private Sql() { }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    public static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    public static long insert(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, parameters);
            if (statement.executeUpdate() != 1) {
                throw new SQLDataException("Expected one inserted row");
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLDataException("Insert did not return a generated key");
                }
                return keys.getLong(1);
            }
        }
    }

    public static <T> List<T> query(Connection connection, String sql, RowMapper<T> mapper,
                                    Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(mapper.map(rows));
                }
                return List.copyOf(result);
            }
        }
    }

    public static <T> Optional<T> one(Connection connection, String sql, RowMapper<T> mapper,
                                      Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                T result = mapper.map(rows);
                if (rows.next()) {
                    throw new SQLDataException("Expected at most one result row");
                }
                return Optional.of(result);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        statement.setQueryTimeout(15);
        for (int i = 0; i < parameters.length; i++) {
            Object value = parameters[i];
            int index = i + 1;
            if (value instanceof Instant instant) {
                statement.setTimestamp(index, Timestamp.from(instant));
            } else if (value instanceof LocalDate date) {
                statement.setDate(index, Date.valueOf(date));
            } else if (value instanceof LocalDateTime dateTime) {
                statement.setTimestamp(index, Timestamp.valueOf(dateTime));
            } else if (value instanceof OffsetDateTime dateTime) {
                statement.setTimestamp(index, Timestamp.from(dateTime.toInstant()));
            } else if (value instanceof UUID uuid) {
                statement.setString(index, uuid.toString());
            } else if (value instanceof Enum<?> enumeration) {
                statement.setString(index, enumeration.name());
            } else {
                statement.setObject(index, value);
            }
        }
    }
}
