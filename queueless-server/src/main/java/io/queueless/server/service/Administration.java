package io.queueless.server.service;

import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import java.sql.*;
import java.time.*;
import java.util.Set;
import static io.queueless.server.service.AppException.require;

/** Configuration commands share the same receipt/transaction boundary as operational commands. */
final class Administration {
    static final Set<String> OPERATIONS=Set.of("CREATE_SERVICE","UPDATE_SERVICE","HOURS","CLOSE_DATE","OPEN_DATE","CREATE_COUNTER","ASSIGN_STAFF","UNASSIGN_STAFF","CREATE_SLOT","UPDATE_SLOT");
    private final Clock clock;
    Administration(Clock clock) { this.clock=clock; }
    Api.Result execute(Connection c,Api.User actor,Api.Command cmd) throws SQLException {
        if(!actor.role().equals("ADMIN")) throw new AppException(403,"FORBIDDEN","Administrator access is required.");
        long id=cmd.entityId(); String message="Configuration saved.";
        switch(cmd.operation()) {
            case "CREATE_SERVICE" -> {
                id=Sql.insert(c,"INSERT INTO services(name,description) VALUES(?,?)",text(cmd,"name",100),text(cmd,"description",500));
                Sql.update(c,"INSERT INTO service_runtime(service_id) VALUES(?)",id);
                for(int day=1;day<=5;day++) Sql.update(c,"INSERT INTO service_hours(service_id,day_of_week,opens,closes) VALUES(?,?,'09:00:00','17:00:00')",id,day);
                message="Service created with Monday–Friday 09:00–17:00 hours. Configure counters and arrival windows.";
            }
            case "UPDATE_SERVICE" -> {
                String policy=text(cmd,"policy",30); require(Set.of("FIFO","APPOINTMENT_AWARE").contains(policy),"Unknown queue policy.");
                Sql.update(c,"UPDATE services SET name=?,description=?,policy=?,open=?,walk_in_limit=?,planned_minutes=?,reference_minutes=? WHERE id=?",
                        text(cmd,"name",100),text(cmd,"description",500),policy,Boolean.parseBoolean(cmd.fields().get("open")),number(cmd,"walkInLimit",1,10000),number(cmd,"plannedMinutes",0,100000),number(cmd,"referenceMinutes",1,480),cmd.serviceId());
                id=cmd.serviceId();
                if(!Boolean.parseBoolean(cmd.fields().get("open"))) {
                    Sql.update(c,"UPDATE appointments SET disruption_protected=TRUE WHERE service_id=? AND state='BOOKED'",cmd.serviceId());
                    Sql.update(c,"INSERT IGNORE INTO notification_work(user_id,event_key,message) SELECT user_id,CONCAT('disruption:',id,':',?),? FROM appointments WHERE service_id=? AND state IN ('BOOKED','CHECKED_IN')",cmd.requestId(),"Your service has paused admissions. Your reservation is retained; contact staff about delays.",cmd.serviceId());
                }
                message="Service updated. Planned minutes are advisory; existing reservations and visits remain valid.";
            }
            case "HOURS" -> {
                int day=number(cmd,"day",1,7); LocalTime opens=time(cmd,"opens"),closes=time(cmd,"closes"); require(opens.isBefore(closes),"Opening time must be before closing time.");
                Sql.update(c,"INSERT INTO service_hours(service_id,day_of_week,opens,closes) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE opens=VALUES(opens),closes=VALUES(closes)",cmd.serviceId(),day,opens,closes); id=cmd.serviceId();
                message="Working hours updated. Existing appointment windows remain reserved.";
            }
            case "CLOSE_DATE" -> {
                LocalDate date=date(cmd,"date");
                Sql.update(c,"INSERT INTO service_closures(service_id,closed_date,reason) VALUES(?,?,?) ON DUPLICATE KEY UPDATE reason=VALUES(reason)",cmd.serviceId(),date,text(cmd,"reason",500)); id=cmd.serviceId();
                Sql.update(c,"UPDATE appointments a JOIN appointment_slots s ON s.id=a.slot_id SET a.disruption_protected=TRUE WHERE a.service_id=? AND a.state='BOOKED' AND s.starts_at>=? AND s.starts_at<?",cmd.serviceId(),date.atStartOfDay(QueueService.ZONE).toInstant(),date.plusDays(1).atStartOfDay(QueueService.ZONE).toInstant());
                // Announce disruption to affected bookings without deleting them or changing inventory.
                Sql.update(c,"INSERT IGNORE INTO notification_work(user_id,event_key,message) SELECT a.user_id,CONCAT('closure:',a.id,':',?),? FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id WHERE a.service_id=? AND a.state IN ('BOOKED','CHECKED_IN') AND s.starts_at>=? AND s.starts_at<?",
                        cmd.requestId(),"Your appointment service has a closure on "+date+". Contact staff to rebook; your reservation has been retained.",cmd.serviceId(),date.atStartOfDay(QueueService.ZONE).toInstant(),date.plusDays(1).atStartOfDay(QueueService.ZONE).toInstant());
                message="Date closed to new admissions. Existing reservations retained; affected customers notified.";
            }
            case "OPEN_DATE" -> { Sql.update(c,"DELETE FROM service_closures WHERE service_id=? AND closed_date=?",cmd.serviceId(),date(cmd,"date")); id=cmd.serviceId(); }
            case "CREATE_COUNTER" -> id=Sql.insert(c,"INSERT INTO counters(service_id,name) VALUES(?,?)",cmd.serviceId(),text(cmd,"name",100));
            case "ASSIGN_STAFF", "UNASSIGN_STAFF" -> {
                long user=number(cmd,"userId",1,Integer.MAX_VALUE);
                require(Sql.one(c,"SELECT id FROM users WHERE id=? AND active=TRUE",r->r.getLong(1),user).isPresent(),"Account not found.");
                if(cmd.operation().equals("ASSIGN_STAFF")) {
                    Sql.update(c,"UPDATE users SET role='STAFF' WHERE id=? AND role='USER'",user);
                    Sql.update(c,"INSERT INTO staff_assignments(user_id,service_id) VALUES(?,?) ON DUPLICATE KEY UPDATE user_id=user_id",user,cmd.serviceId());
                } else {
                    require(Sql.one(c,"SELECT id FROM counters WHERE service_id=? AND owner_id=? LIMIT 1",r->r.getLong(1),cmd.serviceId(),user).isEmpty(),"Release or take over this staff member's counters before removing the assignment.");
                    Sql.update(c,"DELETE FROM staff_assignments WHERE user_id=? AND service_id=?",user,cmd.serviceId());
                }
                id=user;
            }
            case "CREATE_SLOT" -> {
                Instant starts=instant(cmd,"startsAt"),ends=instant(cmd,"endsAt");
                require(starts.isAfter(clock.instant()) && ends.isAfter(starts) && !ends.isAfter(starts.plusSeconds(4*3600)),"Choose a future arrival window lasting at most four hours.");
                require(Sql.one(c,"SELECT id FROM appointment_slots WHERE service_id=? AND starts_at<? AND ends_at>? LIMIT 1",r->r.getLong(1),cmd.serviceId(),ends,starts).isEmpty(),"Arrival windows for this service must not overlap.");
                requireWindow(c,cmd.serviceId(),starts,ends);
                int quota=number(cmd,"capacity",1,10000);
                int plan=Sql.one(c,"SELECT planned_minutes FROM services WHERE id=?",r->r.getInt(1),cmd.serviceId()).orElseThrow();
                int reference=Sql.one(c,"SELECT reference_minutes FROM services WHERE id=?",r->r.getInt(1),cmd.serviceId()).orElseThrow();
                var date=starts.atZone(QueueService.ZONE).toLocalDate();
                long published=Sql.one(c,"SELECT COALESCE(SUM(capacity),0) FROM appointment_slots WHERE service_id=? AND starts_at>=? AND starts_at<?",r->r.getLong(1),cmd.serviceId(),date.atStartOfDay(QueueService.ZONE).toInstant(),date.plusDays(1).atStartOfDay(QueueService.ZONE).toInstant()).orElse(0L);
                if((published+quota)*reference>plan) require(Boolean.parseBoolean(cmd.fields().get("acknowledgeShortfall")),"Published quotas exceed the advisory staffing plan. Confirm the shortfall to publish intentionally.");
                id=Sql.insert(c,"INSERT INTO appointment_slots(service_id,starts_at,ends_at,capacity) VALUES(?,?,?,?)",cmd.serviceId(),starts,ends,quota);
                message="Arrival window published. Reserved capacity is a booking quota, not a start-time guarantee.";
            }
            case "UPDATE_SLOT" -> {
                int quota=number(cmd,"capacity",1,10000);
                require(Sql.update(c,"UPDATE appointment_slots SET capacity=? WHERE id=? AND service_id=? AND reserved<=?",quota,cmd.entityId(),cmd.serviceId(),quota)==1,"Capacity cannot be below retained reservations, and the slot must belong to this service.");
            }
            default -> throw new AppException(400,"INVALID_OPERATION","Unknown administrator operation.");
        }
        Sql.insert(c,"INSERT INTO audit_events(actor_id,service_id,action,entity_id,detail) VALUES(?,?,?,?,?)",actor.id(),cmd.serviceId()>0?cmd.serviceId():null,cmd.operation(),id,message);
        return new Api.Result(id,0,message,false);
    }
    static void requireWindow(Connection c,long service,Instant starts,Instant ends) throws SQLException {
        var start=starts.atZone(QueueService.ZONE); var end=ends.atZone(QueueService.ZONE);
        require(start.toLocalDate().equals(end.toLocalDate()),"Arrival windows must stay within one business day.");
        require(Sql.one(c,"SELECT h.service_id FROM service_hours h JOIN services s ON s.id=h.service_id WHERE h.service_id=? AND h.day_of_week=? AND h.opens<=? AND h.closes>=? AND s.open=TRUE",r->r.getLong(1),service,start.getDayOfWeek().getValue(),start.toLocalTime(),end.toLocalTime()).isPresent(),"This window is outside the service's current working hours.");
        require(Sql.one(c,"SELECT service_id FROM service_closures WHERE service_id=? AND closed_date=?",r->r.getLong(1),service,start.toLocalDate()).isEmpty(),"This service is closed on that date.");
    }
    private static String text(Api.Command c,String field,int max) {
        var value=c.fields().getOrDefault(field,"").strip();
        if(value.isBlank() || value.length()>max) throw new AppException(400,"INVALID_INPUT",field+" must contain 1–"+max+" characters.");
        return value;
    }
    private static int number(Api.Command c,String field,int min,int max) {
        try { int n=Integer.parseInt(c.fields().getOrDefault(field,"")); if(n<min || n>max) throw new NumberFormatException(); return n; }
        catch(NumberFormatException e) { throw new AppException(400,"INVALID_INPUT",field+" must be between "+min+" and "+max+"."); }
    }
    private static LocalTime time(Api.Command c,String field) { try {return LocalTime.parse(text(c,field,20));} catch(DateTimeException e){throw new AppException(400,"INVALID_INPUT","Invalid time.");} }
    private static Instant instant(Api.Command c,String field) {try{return Instant.parse(text(c,field,40));}catch(DateTimeException e){throw new AppException(400,"INVALID_INPUT","Invalid timestamp.");}}
    private static LocalDate date(Api.Command c,String field) {try{return LocalDate.parse(text(c,field,10));}catch(DateTimeException e){throw new AppException(400,"INVALID_INPUT","Invalid date.");}}
}
