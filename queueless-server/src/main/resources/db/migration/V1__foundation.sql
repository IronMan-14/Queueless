CREATE TABLE users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL, role ENUM('USER','STAFF','ADMIN') NOT NULL DEFAULT 'USER',
 active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE services (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL UNIQUE, description VARCHAR(500) NOT NULL,
 policy ENUM('FIFO','APPOINTMENT_AWARE') NOT NULL DEFAULT 'APPOINTMENT_AWARE', open BOOLEAN NOT NULL DEFAULT TRUE,
 walk_in_limit INT NOT NULL DEFAULT 100, planned_minutes INT NOT NULL DEFAULT 480, reference_minutes INT NOT NULL DEFAULT 10,
 CHECK(walk_in_limit > 0), CHECK(planned_minutes >= 0), CHECK(reference_minutes > 0)
) ENGINE=InnoDB;
CREATE TABLE service_runtime (
 service_id BIGINT PRIMARY KEY, ready_sequence BIGINT NOT NULL DEFAULT 0, appointment_streak INT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 0, FOREIGN KEY(service_id) REFERENCES services(id), CHECK(appointment_streak BETWEEN 0 AND 2)
) ENGINE=InnoDB;
CREATE TABLE service_hours (
 service_id BIGINT NOT NULL, day_of_week INT NOT NULL, opens TIME NOT NULL, closes TIME NOT NULL,
 PRIMARY KEY(service_id,day_of_week), FOREIGN KEY(service_id) REFERENCES services(id),
 CHECK(day_of_week BETWEEN 1 AND 7), CHECK(opens < closes)
) ENGINE=InnoDB;
CREATE TABLE service_closures (
 service_id BIGINT NOT NULL, closed_date DATE NOT NULL, reason VARCHAR(500) NOT NULL,
 PRIMARY KEY(service_id,closed_date), FOREIGN KEY(service_id) REFERENCES services(id)
) ENGINE=InnoDB;
CREATE TABLE staff_assignments (
 user_id BIGINT NOT NULL, service_id BIGINT NOT NULL, PRIMARY KEY(user_id,service_id),
 FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(service_id) REFERENCES services(id)
) ENGINE=InnoDB;
CREATE TABLE counters (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, service_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
 owner_id BIGINT NULL, owner_session VARCHAR(100) NULL, claim_version BIGINT NOT NULL DEFAULT 0, paused BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE(service_id,name), UNIQUE(id,service_id), FOREIGN KEY(service_id) REFERENCES services(id), FOREIGN KEY(owner_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE queue_sessions (
 service_id BIGINT NOT NULL, business_date DATE NOT NULL, last_token INT NOT NULL DEFAULT 0,
 PRIMARY KEY(service_id,business_date), FOREIGN KEY(service_id) REFERENCES services(id)
) ENGINE=InnoDB;
CREATE TABLE queue_entries (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, service_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 business_date DATE NOT NULL, token_number INT NOT NULL,
 state ENUM('WAITING','CALLED','SERVING','SKIPPED','COMPLETED','CANCELLED','NO_SHOW','ABORTED') NOT NULL,
 source ENUM('WALK_IN','APPOINTMENT') NOT NULL DEFAULT 'WALK_IN', dispatch_class ENUM('W','A') NOT NULL DEFAULT 'W',
 ready_sequence BIGINT NOT NULL, eligible_at TIMESTAMP(6) NOT NULL, appointment_start TIMESTAMP(6) NULL,
 counter_id BIGINT NULL, version BIGINT NOT NULL DEFAULT 0, requeue_count INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), called_at TIMESTAMP(6) NULL,
 started_at TIMESTAMP(6) NULL, finished_at TIMESTAMP(6) NULL, return_deadline TIMESTAMP(6) NULL,
 unresolved_user BIGINT GENERATED ALWAYS AS (CASE WHEN state IN ('WAITING','CALLED','SERVING','SKIPPED') THEN user_id ELSE NULL END) STORED,
 occupied_counter BIGINT GENERATED ALWAYS AS (CASE WHEN state IN ('CALLED','SERVING') THEN counter_id ELSE NULL END) STORED,
 UNIQUE(service_id,business_date,token_number), UNIQUE(service_id,unresolved_user), UNIQUE(occupied_counter),
 FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(service_id,business_date) REFERENCES queue_sessions(service_id,business_date),
 FOREIGN KEY(counter_id,service_id) REFERENCES counters(id,service_id),
 CHECK((state IN ('CALLED','SERVING') AND counter_id IS NOT NULL) OR (state NOT IN ('CALLED','SERVING') AND counter_id IS NULL)),
 INDEX dispatch_candidates(service_id,state,dispatch_class,ready_sequence)
) ENGINE=InnoDB;
CREATE TABLE queue_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, visit_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, action VARCHAR(40) NOT NULL,
 detail VARCHAR(500) NOT NULL DEFAULT '', created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(visit_id) REFERENCES queue_entries(id), FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE command_receipts (
 actor_id BIGINT NOT NULL, request_id CHAR(36) NOT NULL, fingerprint CHAR(64) NOT NULL,
 status VARCHAR(10) NOT NULL, result_json TEXT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), completed_at TIMESTAMP(6) NULL,
 PRIMARY KEY(actor_id,request_id), FOREIGN KEY(actor_id) REFERENCES users(id),
 CHECK(status IN ('PENDING','DONE')), CHECK(status <> 'DONE' OR result_json IS NOT NULL)
) ENGINE=InnoDB;
CREATE TABLE audit_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, actor_id BIGINT NOT NULL, service_id BIGINT NULL, action VARCHAR(50) NOT NULL,
 entity_id BIGINT NOT NULL, detail VARCHAR(500) NOT NULL DEFAULT '', created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(actor_id) REFERENCES users(id), FOREIGN KEY(service_id) REFERENCES services(id)
) ENGINE=InnoDB;
