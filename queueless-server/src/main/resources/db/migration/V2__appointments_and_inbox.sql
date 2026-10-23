CREATE TABLE appointment_slots (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, service_id BIGINT NOT NULL, starts_at TIMESTAMP(6) NOT NULL, ends_at TIMESTAMP(6) NOT NULL,
 capacity INT NOT NULL, reserved INT NOT NULL DEFAULT 0,
 UNIQUE(service_id,starts_at), UNIQUE(id,service_id), FOREIGN KEY(service_id) REFERENCES services(id),
 CHECK(ends_at > starts_at), CHECK(capacity > 0), CHECK(reserved BETWEEN 0 AND capacity)
) ENGINE=InnoDB;
CREATE TABLE appointments (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, service_id BIGINT NOT NULL, slot_id BIGINT NOT NULL,
 state ENUM('BOOKED','CHECKED_IN','FULFILLED','CANCELLED','NO_SHOW','ABORTED') NOT NULL DEFAULT 'BOOKED',
 reservation_released BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0, visit_id BIGINT NULL UNIQUE,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(slot_id,service_id) REFERENCES appointment_slots(id,service_id),
 FOREIGN KEY(visit_id) REFERENCES queue_entries(id), INDEX(user_id,state)
) ENGINE=InnoDB;
CREATE TABLE appointment_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, appointment_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 action VARCHAR(50) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(appointment_id) REFERENCES appointments(id), FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE notification_work (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, event_key VARCHAR(100) NOT NULL UNIQUE,
 message VARCHAR(500) NOT NULL, due_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 done BOOLEAN NOT NULL DEFAULT FALSE, FOREIGN KEY(user_id) REFERENCES users(id), INDEX pending_work(done,due_at)
) ENGINE=InnoDB;
CREATE TABLE notifications (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, work_id BIGINT NOT NULL UNIQUE,
 message VARCHAR(500) NOT NULL, is_read BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(work_id) REFERENCES notification_work(id), INDEX(user_id,is_read)
) ENGINE=InnoDB;
