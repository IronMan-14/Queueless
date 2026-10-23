package io.queueless.server.service;

import de.mkammerer.argon2.Argon2Factory;
import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Arrays;
import java.util.Locale;

public final class AuthService {
    private final DataSource db;
    private final String dummyHash = hash(java.util.UUID.randomUUID().toString());
    public AuthService(DataSource db) { this.db = db; }
    public Api.User login(Api.Login input) throws SQLException {
        if (input == null || input.password() == null || input.password().length() > 256) throw invalid();
        try (var c = db.getConnection()) {
            var rows = Sql.query(c, "SELECT id,name,role,password_hash FROM users WHERE email=? AND active=TRUE",
                    r -> new LoginRow(new Api.User(r.getLong(1), r.getString(2), r.getString(3)), r.getString(4)), email(input.email()));
            char[] password = input.password().toCharArray();
            try {
                boolean matches = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).verify(rows.isEmpty() ? dummyHash : rows.getFirst().hash(), password);
                if (rows.isEmpty() || !matches) throw invalid();
                return rows.getFirst().user();
            } finally { Arrays.fill(password, '\0'); }
        }
    }
    public Api.User register(Api.Registration input) throws SQLException {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 100)
            throw new AppException(400, "INVALID_INPUT", "A name of 1–100 characters is required.");
        var mail = email(input.email());
        var hash = hash(input.password());
        try (var c = db.getConnection()) {
            try {
                long id = Sql.insert(c, "INSERT INTO users(name,email,password_hash) VALUES(?,?,?)", input.name().strip(), mail, hash);
                return new Api.User(id, input.name().strip(), "USER");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1062) throw AppException.conflict("Registration could not be completed with these details.");
                throw e;
            }
        }
    }
    public Api.User current(long id) throws SQLException {
        try (var c = db.getConnection()) {
            return Sql.one(c, "SELECT id,name,role FROM users WHERE id=? AND active=TRUE",
                    r -> new Api.User(r.getLong(1), r.getString(2), r.getString(3)), id).orElseThrow(AuthService::invalid);
        }
    }
    public static String hash(String password) {
        if (password == null || password.length() < 12 || password.length() > 256)
            throw new AppException(400, "INVALID_INPUT", "Use a password of 12–256 characters.");
        var chars = password.toCharArray();
        try { return Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).hash(3, 19456, 1, chars); }
        finally { Arrays.fill(chars, '\0'); }
    }
    private static String email(String value) {
        if (value != null) value = value.strip();
        if (value == null || value.length() > 254 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new AppException(400, "INVALID_INPUT", "Enter a valid email address.");
        return value.strip().toLowerCase(Locale.ROOT);
    }
    private static AppException invalid() { return new AppException(401, "AUTHENTICATION", "Sign in with valid credentials."); }
    private record LoginRow(Api.User user, String hash) { }
}
