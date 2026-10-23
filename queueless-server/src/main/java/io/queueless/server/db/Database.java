package io.queueless.server.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/** Explicit connection configuration; creating a pool does not migrate or seed the database. */
public final class Database {
    private Database() { }

    public static HikariDataSource create() {
        String password = setting("queueless.db.password", "QUEUELESS_DB_PASSWORD", null);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set QUEUELESS_DB_PASSWORD or queueless.db.password before starting the server");
        }
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(setting("queueless.db.url", "QUEUELESS_DB_URL",
                "jdbc:mysql://127.0.0.1:3307/queueless?connectionTimeZone=UTC"));
        config.setUsername(setting("queueless.db.user", "QUEUELESS_DB_USER", "queueless"));
        config.setPassword(password);
        config.setPoolName("QueueLessDatabase");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setValidationTimeout(3_000);
        config.addDataSourceProperty("connectTimeout", "5000");
        config.addDataSourceProperty("socketTimeout", "20000");
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setConnectionInitSql("SET time_zone = '+00:00', innodb_lock_wait_timeout = 5");
        return new HikariDataSource(config);
    }

    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .validateMigrationNaming(true)
                .load()
                .migrate();
    }

    private static String setting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(environment);
        }
        return value == null ? fallback : value;
    }
}
