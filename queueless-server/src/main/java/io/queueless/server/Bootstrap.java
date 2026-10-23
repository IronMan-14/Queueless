package io.queueless.server;

import io.queueless.server.db.Database;
import io.queueless.server.db.Sql;
import io.queueless.server.service.AuthService;
import javax.sql.DataSource;
import java.sql.SQLException;

/** Explicit local demonstration setup. Never runs automatically on application startup. */
public final class Bootstrap {
    private Bootstrap() { }
    public static void main(String[] args) throws SQLException {
        try(var db=Database.create()) {
            Database.migrate(db);
            String mode=args.length==0?"migrate":args[0];
            if(mode.equals("demo")) seed(db,System.getenv("QUEUELESS_SEED_PASSWORD"));
            else if(mode.equals("admin")) administrator(db);
            else if(!mode.equals("migrate")) throw new IllegalArgumentException("Choose migrate, admin or demo.");
        }
    }
    private static void administrator(DataSource db) throws SQLException {
        String email=System.getenv("QUEUELESS_ADMIN_EMAIL"),name=System.getenv("QUEUELESS_ADMIN_NAME");
        if(email==null || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length()>254 || name==null || name.isBlank() || name.length()>100)
            throw new IllegalArgumentException("Set QUEUELESS_ADMIN_EMAIL and QUEUELESS_ADMIN_NAME.");
        String hash=AuthService.hash(System.getenv("QUEUELESS_ADMIN_PASSWORD"));
        try(var c=db.getConnection()) {
            Sql.insert(c,"INSERT INTO users(name,email,password_hash,role) VALUES(?,?,?,'ADMIN')",name.strip(),email.strip().toLowerCase(java.util.Locale.ROOT),hash);
        }
    }
    public static void seed(DataSource db,String password) throws SQLException {
        String hash=AuthService.hash(password);
        try(var c=db.getConnection()) {
            c.setAutoCommit(false);
            try {
                if(Sql.one(c,"SELECT COUNT(*) FROM users",r->r.getLong(1)).orElseThrow()!=0) throw new IllegalStateException("Demo seeding requires an empty users table.");
                long admin=Sql.insert(c,"INSERT INTO users(name,email,password_hash,role) VALUES('Administrator','admin@queueless.local',?,'ADMIN')",hash);
                long staff=Sql.insert(c,"INSERT INTO users(name,email,password_hash,role) VALUES('Front desk','staff@queueless.local',?,'STAFF')",hash);
                Sql.insert(c,"INSERT INTO users(name,email,password_hash,role) VALUES('Demo visitor','visitor@queueless.local',?,'USER')",hash);
                for(var name:new String[]{"Student Services","Document Desk","General Enquiries"}) {
                    long service=Sql.insert(c,"INSERT INTO services(name,description) VALUES(?,?)",name,"Book an arrival window or join the walk-in queue.");
                    Sql.update(c,"INSERT INTO service_runtime(service_id) VALUES(?)",service);
                    Sql.update(c,"INSERT INTO staff_assignments(user_id,service_id) VALUES(?,?)",staff,service);
                    for(int day=1;day<=7;day++) Sql.update(c,"INSERT INTO service_hours(service_id,day_of_week,opens,closes) VALUES(?,?,'00:00:00','23:59:59')",service,day);
                    for(int i=1;i<=2;i++) Sql.insert(c,"INSERT INTO counters(service_id,name) VALUES(?,?)",service,"Counter "+i);
                    Sql.insert(c,"INSERT INTO audit_events(actor_id,service_id,action,entity_id,detail) VALUES(?,?,'DEMO_SEED',?,'Explicit demo fixture; configure real hours before production')",admin,service,service);
                }
                c.commit();
            } catch(SQLException|RuntimeException e) { c.rollback(); throw e; }
        }
    }
}
