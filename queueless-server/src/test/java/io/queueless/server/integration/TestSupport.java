package io.queueless.server.integration;

import com.zaxxer.hikari.*;
import io.queueless.contracts.Api;
import io.queueless.server.db.*;
import java.sql.*;
import java.util.*;

final class TestSupport {
    static HikariDataSource database() {
        String url=System.getenv("QUEUELESS_TEST_DB_URL");
        if(url==null || !url.matches("jdbc:mysql://[^/]+/[a-zA-Z0-9_]*_test(?:\\?.*)?")) throw new IllegalStateException("Integration tests require a dedicated database ending _test");
        var config=new HikariConfig(); config.setJdbcUrl(url); config.setUsername(System.getenv("QUEUELESS_DB_USER")); config.setPassword(System.getenv("QUEUELESS_DB_PASSWORD"));
        config.setMaximumPoolSize(16); config.setConnectionInitSql("SET time_zone = '+00:00'");
        var pool=new HikariDataSource(config); Database.migrate(pool); return pool;
    }
    record Fixture(long service,Api.User user,Api.User staff,Api.User admin,long counter,long secondCounter) { }
    static Fixture fixture(HikariDataSource db) throws SQLException {
        try(var c=db.getConnection()) {
            String unique=UUID.randomUUID().toString();
            long service=Sql.insert(c,"INSERT INTO services(name,description) VALUES(?,'integration fixture')",unique);
            Sql.update(c,"INSERT INTO service_runtime(service_id) VALUES(?)",service);
            for(int day=1;day<=7;day++) Sql.update(c,"INSERT INTO service_hours(service_id,day_of_week,opens,closes) VALUES(?,?,'00:00:00','23:59:59')",service,day);
            var user=user(c,"USER"); var staff=user(c,"STAFF"); var admin=user(c,"ADMIN");
            Sql.update(c,"INSERT INTO staff_assignments(user_id,service_id) VALUES(?,?)",staff.id(),service);
            long counter=Sql.insert(c,"INSERT INTO counters(service_id,name) VALUES(?,'One')",service);
            long second=Sql.insert(c,"INSERT INTO counters(service_id,name) VALUES(?,'Two')",service);
            return new Fixture(service,user,staff,admin,counter,second);
        }
    }
    static Api.User user(Connection c,String role) throws SQLException {
        long id=Sql.insert(c,"INSERT INTO users(name,email,password_hash,role) VALUES('Test user',?,'not-a-login-hash',?)",UUID.randomUUID()+"@example.test",role);
        return new Api.User(id,"Test user",role);
    }
    static Api.Command command(String op,long service,long entity,long counter,long version,long claim,Map<String,String> fields) {
        return new Api.Command(UUID.randomUUID().toString(),op,service,entity,counter,version,claim,fields);
    }
    static long count(HikariDataSource db,String sql,Object... args) throws SQLException {
        try(var c=db.getConnection()) { return Sql.one(c,sql,r->r.getLong(1),args).orElseThrow(); }
    }
}
