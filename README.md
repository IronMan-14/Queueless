# QueueLess

A shared Java backend for queue and appointment management. The browser uses JSP/EL/custom tags and Servlets; the JavaFX operator console uses the same service rules through a JSON HTTP API. JDBC and MySQL remain visible application components. There is no Spring or ORM.

The application implements walk-in queues, appointments and check-in, multiple staff counters, fair dispatch, persistent notifications, service administration, history, reports, CSV export and a JavaFX operator console. Read [verification evidence](docs/verification.md) for executed builds, real MySQL concurrency checks, browser recovery and desktop workflow results. Public hosting is deferred; container execution and the complete evaluator presentation remain separate acceptance gates.

## Project layout

- `queueless-contracts`: version 1 transport DTOs; no database or UI dependency.
- `queueless-server`: Servlet/JSP WAR, application services, explicit JDBC, Flyway SQL migrations, background inbox processing.
- `queueless-desktop`: JavaFX staff application, Java HTTP client, restricted local preference serialization.
- `docs`: architecture, syllabus mapping, acceptance milestones and evaluator checklist.

## Build and test

Prerequisites: JDK 21, Maven 3.9.11, MySQL 8.4.10. JavaFX 21.0.9 is resolved by Maven. The WAR targets Tomcat 10.1.60 (Servlet 6 / JSP 3.1); use `jakarta.servlet`, not `javax.servlet`.

```powershell
# With JDK/Maven installed, or workspace tools under .tools:
.\build.ps1 '-B' '-ntp' 'verify'
# Include real MySQL tests using the existing local settings:
.\verify.ps1 -LocalDatabase -MultiProcess
# Then exercise actual JavaFX controls on a separate test server:
.\scripts\verify-desktop-ui.ps1 -SkipBuild
```

On any OS with Maven installed, `mvn -B -ntp verify` builds the same reactor. Database tests run only when `QUEUELESS_TEST_DB_URL` is explicitly supplied; it must name a dedicated database ending `_test`. Set `QUEUELESS_DB_USER` and `QUEUELESS_DB_PASSWORD`. Skipped database tests are **not** evidence of transaction correctness. Tests create isolated fixtures, never truncate application data.

The Windows JavaFX runner requires the existing local tools and dedicated `queueless_test` database. It starts its own temporary backend on port 18081 and checks a per-run marker before creating fixtures. Run it after the database suite, never concurrently. Use `-SkipBuild` only after packaging the current source; otherwise omit it. The ordinary Maven suite intentionally skips this native GUI test. Never point GUI test fixtures at the demonstration application.

## Existing Windows development environment

Workspace tooling is isolated in ignored `.tools`; database data and generated credentials are in ignored `.local`. These are not part of a clone. The setup instructions for a new machine are in [deployment](docs/deployment.md).

1. Run `scripts/start-local-database.ps1` in a terminal (port 3307, loopback only).
2. Build with `build.ps1 '-B' '-ntp' 'install'`.
3. Apply migrations with `scripts/bootstrap-local.ps1 migrate`.
4. On an **empty** demonstration database only, `scripts/bootstrap-local.ps1 demo` creates three services, two counters per service, and demo accounts.
5. Run `scripts/start-local-server.ps1` in another terminal.
6. Open `http://localhost:8080/queueless/`, then run `scripts/start-desktop.ps1` for JavaFX.

Demo accounts are `admin@queueless.local`, `staff@queueless.local`, and `visitor@queueless.local`. Their generated local password is the `QUEUELESS_SEED_PASSWORD` value in `.local/settings.json`. It is intentionally not printed in logs or committed. These accounts and all-day service hours are demonstration fixtures, not production defaults.

For a production administrator, run `io.queueless.server.Bootstrap admin` with `QUEUELESS_ADMIN_NAME`, `QUEUELESS_ADMIN_EMAIL` and `QUEUELESS_ADMIN_PASSWORD` in the environment. This command explicitly creates an ADMIN account; web registration always creates USER accounts. Duplicate emails are rejected.

## Product rules

- Slots reserve arrival windows, not guaranteed start times. Published reservation quota, advisory counter-minute planning, and current counter availability are separate values.
- One unresolved visit per customer/service includes recoverable skipped visits. Future non-overlapping appointments do not count as unresolved visits.
- Appointment-aware dispatch permits at most two appointment dispatches before a waiting walk-in dispatch, across all counters for one service. Cancellation after a committed dispatch does not refund a turn.
- Check-in normally opens 10 minutes before a window and closes 10 minutes after its end. Skipped visits have a 15-minute return window and one requeue at the walk-in tail.
- Ownership is bound to a staff session and version. Explicit resume/takeover fences old clients and preserves occupied visits. There are no timed counter leases.
- Successful commands, receipts, histories and notification work commit together. Retry an uncertain outcome using the **same** request UUID and unchanged input.
- Notifications are an internal persistent inbox. No email/SMS provider or external-message guarantee is implied.
- Reports use stored events/timestamps; missing samples are shown as unavailable. Wait estimates require at least five completed samples from the previous 30 days and currently claimed/unpaused counters. They approximate dispatch waves, assume a full average duration remains at each occupied counter, and are not service guarantees.

See [architecture](docs/architecture.md), [syllabus coverage](docs/syllabus-coverage.md), [demonstration](docs/demonstration.md), and [deployment](docs/deployment.md).

After a successful verification run, `scripts/package-release.ps1` creates a source/deployment ZIP under ignored `dist`, including the built `artifacts/queueless.war` and SHA-256 manifest. Local credentials, database files, caches and test fixtures are excluded. The JavaFX source builds with Maven on the operator's platform; the package does not claim to be a native desktop installer.
