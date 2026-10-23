# QueueLess staff console

The JavaFX console uses the same Servlet API as the browser application. It never connects to MySQL. Start the backend before signing in.

From the repository root, install the local modules, then launch this module:

```powershell
.\build.ps1 '-pl' 'queueless-desktop' '-am' 'install' '-DskipTests'
.\build.ps1 '-f' 'queueless-desktop/pom.xml' 'javafx:run'
```

The login screen defaults to `http://localhost:8080/queueless`. Enter the deployed HTTPS address for remote operation. Plain HTTP is allowed only for loopback hosts. Use a staff account assigned to a service or an administrator account.

Select a counter, **Claim / resume**, then **Call next**, **Start service**, and **Complete service**. Claiming is explicit after a new login. The server validates assignment, claim version, queue-entry version, and every state transition. Recall, skip, abort, pause, release, and authorized takeover use the same API. A disconnected console does not automatically release a physical counter.

HTTP requests run on a bounded worker executor. Polling is coalesced, obsolete responses cannot replace a newer session's data, and shutdown interrupts workers. When a command's outcome is unknown, **Retry uncertain command** preserves its complete original request; other changes remain blocked until that request is resolved. Session expiry or signing out keeps that request in memory. Sign back in to the same backend with the same account to retry under the new session. Resolve an uncertain command before closing the application; if the application has already closed, reconcile the counter and history before issuing another action.

Appointments are reviewed in their tab. **History** opens cursor-paginated queue or appointment history. Reports use real backend data and can be exported to CSV. Local serialized preferences contain selected IDs, refresh timing, and window size only; they never contain passwords, session cookies, or database credentials. Restore validates both the object type and its fields.

## Tests

```powershell
.\build.ps1 '-pl' 'queueless-desktop' '-am' 'test'
```

The default suite tests HTTP/CSRF/session behavior against a local protocol test server, explicit retry IDs, history decoding, preferences filtering and refresh races. It does not claim to test the deployed backend.

`JavaFxSmokeTest` is an additional **opt-in native GUI integration test**. Run it through the isolated runner after starting the local MySQL test server:

```powershell
.\scripts\verify-desktop-ui.ps1
```

The runner packages the server, requires the dedicated `queueless_test` database from `.local/settings.json`, and starts a separate loopback Tomcat on port **18081**. It creates a random test administrator and a one-run isolation marker inside that temporary server. The GUI test checks that marker before logging in or creating any fixture. The demo URL on port 8080 and arbitrary remote backends are rejected. Use `-SkipBuild` only when the current server WAR has already been packaged. Do not run this test concurrently with the database suite, which resets the shared test database.

The runner stops only its own Tomcat process and restores its environment settings on completion. Test accounts, services, counters, and visits remain in the dedicated test database; they never appear in the demo database. Its temporary server logs remain under ignored `.local/fx-isolated-*` directories for diagnosis. No database rows are deleted by this runner.

The smoke test renders actual JavaFX controls, signs in as an assigned staff fixture, performs claim/call/start/complete/release, verifies the resulting records through the real API, and signs out. Screenshots are written to `target/fx-smoke/`. Passwords are supplied through the environment and are not included in screenshots or test output.
