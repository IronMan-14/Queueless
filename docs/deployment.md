# Run, deploy and recover QueueLess

The verified local environment is Windows, JDK 21, Maven 3.9.11, MySQL 8.4.10 and Tomcat 10.1.60. See `verification.md` for executed checks. Container configuration and the CI workflow are supplied; neither a successful image build nor an online installation should be inferred from configuration alone. The user requested a deployable package and will provide hosting later; public publishing is deferred by that decision.

## Fresh machine with Docker

Install a working Docker Engine with Compose v2, JDK 21 and Maven. Copy `.env.example` to `.env`; replace both password placeholders with different random values. Keep the file private and outside version control. The database is persisted in the named `mysql_data` volume and has no published host port.

```sh
docker compose build
docker compose up -d db
docker compose run --rm app java -cp '/usr/local/tomcat/webapps/queueless/WEB-INF/classes:/usr/local/tomcat/webapps/queueless/WEB-INF/lib/*' io.queueless.server.Bootstrap migrate
```

Create the first administrator explicitly. Set `QUEUELESS_ADMIN_NAME`, `QUEUELESS_ADMIN_EMAIL` and `QUEUELESS_ADMIN_PASSWORD` in the shell environment (use a secret manager or a non-echoing prompt for passwords). Do not place the password directly in a recorded command line. Then run:

```sh
docker compose run --rm -e QUEUELESS_ADMIN_NAME -e QUEUELESS_ADMIN_EMAIL -e QUEUELESS_ADMIN_PASSWORD app java -cp '/usr/local/tomcat/webapps/queueless/WEB-INF/classes:/usr/local/tomcat/webapps/queueless/WEB-INF/lib/*' io.queueless.server.Bootstrap admin
docker compose up -d app
```

Open `http://localhost:8080/queueless/`. Register a staff account as an ordinary user, then assign it to a service using the administrator interface. Configure actual working hours, counters and slots. Demo seeding is optional and only supported when the users table is empty; never run it on a production installation. The application does not automatically migrate or seed on startup.

For a native installation, create MySQL database `queueless` with `utf8mb4`, configure `QUEUELESS_DB_URL`, `QUEUELESS_DB_USER`, `QUEUELESS_DB_PASSWORD`, and build `mvn -B -ntp install`. Run `Bootstrap migrate` with `queueless-server/target/classes` and the exploded WAR's `WEB-INF/lib/*` on the classpath. The separator is `;` on Windows and `:` on Unix. Deploy `queueless-server/target/queueless.war` to Tomcat's `webapps`. Copy `deployment/context.xml` to the dedicated Tomcat instance's `conf/context.xml`. Keep MySQL private and set its session timezone to UTC (the application does this for pooled connections).

## JavaFX operator console

Install JDK 21 on the operator machine, run `mvn -B -ntp install`, then `mvn -pl queueless-desktop javafx:run`. On this Windows workspace, `scripts/start-desktop.ps1` uses the isolated tools. Enter the backend base URL including `/queueless`. HTTP is accepted only for loopback development; remote servers require HTTPS with a trusted certificate. Credentials and session cookies stay in memory. The saved preference file contains layout/selection settings only and uses a restrictive deserialization filter.

## Online HTTPS

Use one Linux host initially. Point a controlled DNS name to it; allow inbound 80/443, restrict administrator SSH, and keep MySQL and Tomcat off public interfaces. Set `QUEUELESS_DOMAIN` in `.env`. Caddy obtains and renews the certificate:

```sh
docker compose -f compose.yaml -f deployment/compose.online.yaml up -d --build
```

Perform migration and administrator setup before opening the application to users. For those commands use the same two `-f` arguments. `QUEUELESS_PUBLIC_ORIGIN` must match the actual browser origin exactly. Secure session cookies follow the HTTPS setting. Caddy persists certificates in `caddy_data`.

The online override reserves `172.30.44.0/24` for this Compose network and `172.30.44.10` for Caddy. `context-online.xml` trusts forwarded addresses **only from that proxy IP**, allowing the login rate limiter to see the actual client address. If that subnet conflicts with the host, change the subnet, proxy address and exact `internalProxies` expression together. Never trust arbitrary client-supplied forwarded headers. Tomcat's published port remains loopback-only for host diagnostics.

After deployment verify `/queueless/api/health`, registration, browser-to-JavaFX queue processing, appointment booking/check-in, inbox delivery, CSV export and a restart. Check that HTTP redirects to HTTPS and cookies have `Secure`, `HttpOnly` and `SameSite=Lax`. Public deployment requires a real domain/host and is a remaining external gate.

## Credentials and privileges

The provided single-host Compose setup uses one schema-scoped database account for migration and runtime. Before accepting real users, create separate migration and runtime accounts: the runtime account needs only `SELECT`, `INSERT`, `UPDATE`, `DELETE` on `queueless.*`; migrations require schema DDL as well. Run migration with the migration account, then start the application with the runtime account. Do not grant global privileges or expose root credentials to the WAR or desktop. Database TLS with hostname verification is required if MySQL is moved off the private host/network.

Sessions are memory-resident and expire after inactivity. Restarting a backend requires signing in again; counter ownership persists, so the same operator explicitly resumes the counter or an administrator performs an audited takeover. The database supports independent backend processes, but this initial deployment is one backend: clustered sessions, rolling deployment and availability guarantees are not claimed. Rate limiting is process-local.

Database connections have a 5-second connect timeout, 20-second socket timeout and 5-second InnoDB lock wait; application JDBC statements have a 15-second query timeout. A command retries at most three complete transactions, keeping its request ID. Client timeouts can happen before the server finishes resolving a transaction, so the receipt protocol remains necessary. A larger migration may require an explicitly longer `socketTimeout` in the migrator's JDBC URL.

Deploy updates by restarting the Tomcat process/container. Do not rely on repeated in-process WAR hot reloads: the Argon2 dependency uses a JNA Cleaner thread, and an embedded-Tomcat shutdown test reported its classloader retention warning. Application executors and JDBC pools are explicitly shut down; process replacement also releases native-library infrastructure.

## Backup and restoration

Back up before migration and regularly afterward. Use a restricted backup account, a protected MySQL option file for credentials, `mysqldump --single-transaction --no-tablespaces --set-gtid-purged=OFF queueless`, and a private encrypted backup destination. Avoid concurrent DDL while taking a transactional dump. Retain the schema migration history, receipts, events and notification work with the business tables. Do not export only current queue rows.

Restore into a **new isolated database** first, never overwrite an active database as a test. Import the SQL, run Flyway validation/migration against that copy, verify table counts and key invariants, start a separate application against it and test login/read operations. The password hashes and customer records in a backup are sensitive. Restrict permissions and define a retention policy before handling real customer data.

`scripts/verify-backup-restore.ps1` is the workspace recovery drill: it dumps only the dedicated local test database, restores it into a fresh isolated MySQL data directory on port 3308, compares source/restored counts and verifies integrity. It refuses to reuse an existing recovery directory and leaves its evidence under ignored `.local` for inspection. It does not overwrite either application database.

## Testing and maintenance

`verify.ps1 -LocalDatabase` runs the reactor with real MySQL tests from ignored local settings. Set `QUEUELESS_MULTI_PROCESS_TEST=true` to include separate-backend races. CI creates an ephemeral `queueless_test` MySQL service. Desktop tests requiring a real graphical session are explicitly opt-in; a skipped graphical test is not visual evidence. Save actual test reports and deployment checks in `verification.md`.

Review pinned dependency/container updates before releases. Apply new migration files instead of editing applied migrations. Do not run `docker compose down -v` on a database whose data must survive. Monitor health, failed transactions, notification backlog and backup restoration results. Logs intentionally omit credentials and command payloads.

Implementation references: [MySQL dump privileges/options](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html), [Tomcat trusted proxy configuration](https://tomcat.apache.org/tomcat-10.1-doc/config/valve.html), [GitHub service-container networking](https://docs.github.com/en/actions/tutorials/use-containerized-services/use-docker-service-containers).
