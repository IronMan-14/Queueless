package io.queueless.server.service;

import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import java.sql.*;
import java.time.*;
import static io.queueless.server.service.AppException.require;

/** A slot reserves an arrival window; its quota never promises an exact service start. */
final class AppointmentService {
    private final Clock clock;
    private final Admissions admissions;
    AppointmentService(Clock clock,Admissions admissions) { this.clock=clock; this.admissions=admissions; }
    private record Slot(long id,Instant starts,Instant ends,int quota,int reserved) { }
    private record Booking(long id,long user,long slot,String state,long version,boolean protectedArrival) { }
    Api.Result execute(Connection c,Api.User actor,Api.Command cmd) throws SQLException {
        if(cmd.operation().equals("BOOK")) {
            Slot slot=slot(c,cmd.entityId(),cmd.serviceId());
            reserve(c,slot,cmd.serviceId(),actor.id(),0);
            long id=Sql.insert(c,"INSERT INTO appointments(user_id,service_id,slot_id) VALUES(?,?,?)",actor.id(),cmd.serviceId(),slot.id());
            Events.appointment(c,id,actor.id(),"BOOKED");
            return new Api.Result(id,0,"Arrival window reserved; service start may vary.",false);
        }
        var booking=Sql.one(c,"SELECT id,user_id,slot_id,state,version,disruption_protected FROM appointments WHERE id=? AND service_id=? FOR UPDATE",
                r->new Booking(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getLong(5),r.getBoolean(6)),cmd.entityId(),cmd.serviceId()).orElseThrow(()->AppException.conflict("Appointment not found."));
        if(booking.user()!=actor.id()) QueueService.staff(c,actor,cmd.serviceId());
        require(booking.version()==cmd.expectedVersion(),"Appointment changed. Refresh before acting.");
        require(booking.state().equals("BOOKED"),"This action requires a booked appointment.");
        Slot original=slot(c,booking.slot(),cmd.serviceId());
        switch(cmd.operation()) {
            case "CANCEL_APPOINTMENT" -> {
                Sql.update(c,"UPDATE appointments SET state='CANCELLED',reservation_released=TRUE,version=version+1 WHERE id=?",booking.id());
                Sql.update(c,"UPDATE appointment_slots SET reserved=reserved-1 WHERE id=? AND reserved>0",original.id());
                Events.appointment(c,booking.id(),actor.id(),"CANCELLED");
            }
            case "RESCHEDULE" -> {
                long destination;
                try { destination=Long.parseLong(cmd.fields().getOrDefault("slotId","0")); }
                catch(NumberFormatException e) { throw new AppException(400,"INVALID_INPUT","Choose a destination slot."); }
                require(destination!=original.id(),"Choose a different appointment window.");
                Slot replacement=slot(c,destination,cmd.serviceId());
                reserve(c,replacement,cmd.serviceId(),booking.user(),booking.id());
                Sql.update(c,"UPDATE appointment_slots SET reserved=reserved-1 WHERE id=? AND reserved>0",original.id());
                Sql.update(c,"UPDATE appointments SET slot_id=?,disruption_protected=FALSE,version=version+1 WHERE id=?",replacement.id(),booking.id());
                Events.appointment(c,booking.id(),actor.id(),"RESCHEDULED");
            }
            case "CHECK_IN" -> {
                var now=clock.instant();
                require(!now.isBefore(original.starts().minusSeconds(600)) && (!now.isAfter(original.ends().plusSeconds(600)) || booking.protectedArrival()),"Check-in opens 10 minutes before the window and closes 10 minutes after it ends.");
                // Existing reservations can record arrival even during an operational disruption.
                var admission=admissions.join(c,booking.user(),cmd.serviceId(),original.starts());
                Sql.update(c,"UPDATE appointments SET state='CHECKED_IN',visit_id=?,version=version+1 WHERE id=?",admission.entityId(),booking.id());
                Events.appointment(c,booking.id(),actor.id(),"CHECKED_IN");
                return new Api.Result(booking.id(),booking.version()+1,"Checked in. "+admission.message(),false);
            }
            default -> throw new AppException(400,"INVALID_OPERATION","Unknown appointment operation.");
        }
        return new Api.Result(booking.id(),booking.version()+1,"Appointment updated.",false);
    }
    private Slot slot(Connection c,long id,long service) throws SQLException {
        return Sql.one(c,"SELECT id,starts_at,ends_at,capacity,reserved FROM appointment_slots WHERE id=? AND service_id=? FOR UPDATE",r->new Slot(r.getLong(1),r.getTimestamp(2).toInstant(),r.getTimestamp(3).toInstant(),r.getInt(4),r.getInt(5)),id,service)
                .orElseThrow(()->AppException.conflict("Appointment slot not found in this service."));
    }
    private void reserve(Connection c,Slot slot,long service,long user,long excluded) throws SQLException {
        require(slot.starts().isAfter(clock.instant()),"Booking closes when the arrival window starts.");
        Administration.requireWindow(c,service,slot.starts(),slot.ends());
        require(slot.reserved()<slot.quota(),"This arrival window is fully booked.");
        require(Sql.one(c,"SELECT a.id FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id WHERE a.user_id=? AND a.id<>? AND a.state<>'CANCELLED' AND s.starts_at<? AND s.ends_at>? LIMIT 1",r->r.getLong(1),user,excluded,slot.ends(),slot.starts()).isEmpty(),"This appointment overlaps an existing reservation.");
        require(Sql.update(c,"UPDATE appointment_slots SET reserved=reserved+1 WHERE id=? AND reserved<capacity",slot.id())==1,"This arrival window is fully booked.");
    }
}
