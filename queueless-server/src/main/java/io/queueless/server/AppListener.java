package io.queueless.server;

import com.zaxxer.hikari.HikariDataSource;
import io.queueless.server.db.Database;
import io.queueless.server.service.BackgroundWork;
import jakarta.servlet.*;
import java.time.Clock;
import java.util.Set;

public final class AppListener implements ServletContextListener {
    private HikariDataSource pool;
    private BackgroundWork background;
    @Override public void contextInitialized(ServletContextEvent event) {
        var context=event.getServletContext();
        context.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));
        var cookie=context.getSessionCookieConfig(); cookie.setHttpOnly(true); cookie.setAttribute("SameSite","Lax");
        String origin=System.getenv("QUEUELESS_PUBLIC_ORIGIN");
        cookie.setSecure(origin!=null && origin.startsWith("https://"));
        pool=Database.create();
        try {
            if(Boolean.parseBoolean(System.getProperty("queueless.migrate",System.getenv("QUEUELESS_MIGRATE")))) Database.migrate(pool);
            background=new BackgroundWork(pool,Clock.systemUTC());
            context.setAttribute("app",new AppContext(pool,Clock.systemUTC(),background::signal));
            background.start();
        } catch(RuntimeException failure) { pool.close(); throw failure; }
    }
    @Override public void contextDestroyed(ServletContextEvent event) {
        if(background!=null) background.close();
        if(pool!=null) pool.close();
        com.mysql.cj.jdbc.AbandonedConnectionCleanupThread.checkedShutdown();
        java.sql.DriverManager.drivers().filter(d->d.getClass().getClassLoader()==getClass().getClassLoader()).forEach(d->{
            try { java.sql.DriverManager.deregisterDriver(d); } catch(java.sql.SQLException ignored) { event.getServletContext().log("JDBC driver deregistration failed"); }
        });
    }
}
