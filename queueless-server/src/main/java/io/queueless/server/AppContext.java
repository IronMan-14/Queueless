package io.queueless.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queueless.server.service.*;
import javax.sql.DataSource;
import java.time.Clock;

public final class AppContext {
    private final ObjectMapper json = new ObjectMapper();
    private final AuthService auth;
    private final QueueService queue;
    private final QueryService queries;
    private final DataSource db;
    public AppContext(DataSource db, Clock clock) {
        this(db,clock,()->{});
    }
    public AppContext(DataSource db, Clock clock, Runnable committed) {
        this.db=db;
        auth=new AuthService(db); queue=new QueueService(db,json,clock,committed); queries=new QueryService(db,clock);
    }
    public ObjectMapper json() { return json; }
    public AuthService auth() { return auth; }
    public QueueService queue() { return queue; }
    public QueryService queries() { return queries; }
    public boolean health() throws java.sql.SQLException {
        try(var c=db.getConnection()) { return io.queueless.server.db.Sql.one(c,"SELECT 1",r->r.getInt(1)).orElse(0)==1; }
    }
}
