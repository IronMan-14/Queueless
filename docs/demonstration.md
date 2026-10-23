# End-to-end demonstration and acceptance checklist

Checked items below have automated or recorded local evidence; they do not claim that an evaluator has watched the entire sequence. Current evidence as of 2026-10-18 covers 96 distinct passing tests: the 10:04:47 IST reactor passed 78 server + 17 desktop tests with the native GUI case intentionally skipped, then the isolated native run passed that case at 10:06:35 IST. All 14 HTTP/JSP/MySQL cases also passed at 10:05:50 IST after the counter JSP correction. These are separate runs, not one zero-skip 96-test execution. The initial attempt failed while MySQL was stopped; it was rerun successfully after database startup. The subsequent CSS-only package skipped tests and adds no test evidence. Unchecked items remain rehearsal or acceptance work. See `syllabus-coverage.md` and `verification.md` for exact scopes. Use test accounts and a dedicated database; do not expose credentials, cookies or personal data during inspection.

## UX audit improvements — October 18 evidence

- [x] Keep Overview focused on summary counts with useful contextual guidance. Observe compact routine success feedback; prominent error/uncertain feedback remains available.
- [x] Show navigation icons and an unread badge derived from stored data. The exercised inbox displayed four unread updates, readable legacy event text and distinct event cues, with an explicit **All services** label.
- [x] Submit the grouped service selector explicitly and retain the current page. The recorded browser check changed to Document Desk while staying on Appointments.
- [x] Inspect the named layouts at 900×800 and configured 390×844. These observations are not a complete device or accessibility audit.
- [x] Change the selected service without submitting, choose **Refresh status**, then **Keep editing**: the confirmation closes and preserves the selection. Refresh from a fragment-only URL fetches a newer server snapshot.
- [x] Verify through real JSP/HTTP tests that history links accompany their lists, calendars accompany admission/booking controls, empty states provide useful next steps, and repeated headings are removed.
- [x] Distinguish daily **Token #** from persistent **Visit reference #**, including visit/history rendering. Terminal visit rows show **No further action**, with no action that revives a cancelled visit.
- [x] Submit the inbox's exact unread snapshot IDs using JSON and native forms. At most 100 IDs are accepted; a larger inbox shows **Mark 100 as read** and retains all unread rows. New arrivals remain unread, including a lower-ID insert committed after the snapshot.
- [x] Verify ownership and CSRF rejection, stale already-read IDs, malformed/oversized batches, simultaneous identical requests, receipt replay, and injected rollback/recovery without partial read flags.
- [x] Verify friendly persisted-event messages, safe unknown-text display and shared JSON compatibility. A skipped notice points to eligibility checks rather than promising an additional return.
- [x] Record the latest live-browser lost-response/navigation-guard sequence: bulk read committed, refresh and sidebar navigation kept the pending request, retry confirmed the original result, and database inspection found one receipt and zero unread test-account messages. Final mobile and desktop screenshots are listed in `verification.md`.
- [ ] Complete the full evaluator and accessibility rehearsal.

## Setup and first connection

- [ ] Start from documented prerequisites and pinned dependencies without editing source for machine-specific settings.
- [ ] Initialize a fresh MySQL database and start the server and JavaFX client.
- [ ] Show the browser and JavaFX reading the same server-backed service records.
- [ ] Explain Servlet/JSP/JDBC and JavaFX responsibilities and module dependencies.
- [ ] Inspect deployed Servlet initialization/request/shutdown behavior and `web.xml` configuration.
- [x] Keep automated native GUI fixtures out of the demonstration application. Run `scripts/verify-desktop-ui.ps1` against its own Tomcat on 18081 and `queueless_test`; its isolation guard rejects normal demo targets and missing/invalid proof. Use `-SkipBuild` only with a current WAR.

## User feedback and test-data separation — October 16 evidence

- [x] The live directory contains the three intended services after a backed-up transaction removed only six identified FX test services and twelve test accounts. Shared administrator receipts were preserved; independent inspection found no remaining FX services/accounts.
- [x] Sign-in shows progress and confirmation. Registration and login/logout one-time notices are covered by the nine HTTP integration tests.
- [x] An attempted duplicate JOIN shows the actual rejection reason without creating another visit.
- [x] Cancellation first asks for confirmation. Choosing **Go back** with Enter preserves the visit; confirming then cancels it and returns to `#visits` with visible feedback.
- [x] The exercised linked cancellation produced exactly one visit event, one appointment event and one committed receipt; the already-consumed arrival quota remained retained.
- [x] In the configured 390×844 view, marking a notification read updates the row, stays on `#notifications` and displays confirmation. The captured mobile screenshot is 375×845. A fragment-only navigation bug found by this check was corrected so the latest data loads.
- [x] Repeatedly drop committed notification-read responses: commands remain locked, **Retry same request** remains available, and sign-out is blocked until the command outcome is resolved. Allow the response through and recover the original receipt without another business action.
- [x] Keyboard sign-out with Enter succeeds at the normal desktop viewport and shows a confirmation; the screenshot is 1280×720.
- [ ] Complete mobile sign-out, screen-reader and broader keyboard-accessibility checks across every view. The named checks above do not constitute a full accessibility audit.

## Separate workspace pages — October 17 evidence

- [x] Overview contains summary counts and links; Queue & visits owns joining, working hours, tokens and visit management. The 17:27:41 IST HTTP run verified customer and operator summary scopes using real admissions. All 11 existing cases passed again.
- [x] Use the compact service picker to select Document Desk and submit with Enter; Queue & visits stays selected. Repeat service switching at phone width and confirm controls remain usable. These are limited browser checks recorded at approximately 17:28 IST.
- [x] Open Overview, Queue & visits, Appointments and Notifications as an ordinary user. Each has its own `view` URL and only its own content; navigation no longer scrolls through a combined page.
- [x] Verify all seven workspace views through actual HTTP/JSP rendering, including staff/admin pages. Unknown views return 404; unauthorized direct requests return 403; staff service restrictions remain enforced.
- [x] Switch to service 2 while on Queue & visits or Appointments; the selected page is preserved. Follow Full appointment history and return to the same service's Appointments page.
- [x] Open a legacy `#notifications` link and observe its migration to `view=notifications`. The historical fragment destinations described above are superseded by page URLs.
- [x] Mark a notification read on its separate page; the updated READ state and confirmation appear while remaining on Notifications. Existing command/replay/appointment/counter feedback tests passed with the new page destinations.
- [x] Inspect Appointments at a configured 390×844 viewport (captured image 375×811), use keyboard Enter to open Queue & visits, then browser Back to return to Appointments. Click Overview and verify its separate summary page.
- [ ] Complete a broader accessibility and full evaluator navigation rehearsal. These named browser checks do not establish exhaustive screen-reader, mobile or keyboard coverage.

## Identity and authorization

- [x] Register an ordinary user; submitting a forged role cannot create staff/admin.
- [x] Log in and log out in browser and JavaFX; an expired/invalidated session rejects later operations.
- [ ] Demonstrate protected-session identity rotation and CSRF enforcement without displaying secrets.
- [ ] Confirm an inactive account and an unassigned staff account cannot operate a service.
- [ ] Attempt another user's booking/history/inbox or out-of-scope report and verify access is denied.

## Configurable services, calendars and counters

- [ ] Create a service through administration and see it in both clients without a code change.
- [ ] Configure valid working hours and reject invalid or overlapping schedule input according to documented rules.
- [ ] Create multiple counters and assign staff to the service.
- [ ] Claim a counter using a staff session; a conflicting claim fails.
- [ ] Show that release requires an empty counter.
- [x] Take over an occupied counter through an authorized action; its entry is preserved and the old claim/version is fenced.
- [ ] Deactivate a service or alter future availability without silently deleting bookings, unresolved visits or history.

## Walk-in workflow and states

- [x] Join an open queue; show the token, entry, creation event and successful command receipt in MySQL.
- [ ] Reject outside-hours, closed or full admission according to the configured rules.
- [x] Prevent another unresolved visit for the same user/service on the same business date.
- [x] Verify a prior-day unresolved visit still blocks both walk-in admission and appointment check-in after day rollover; resolving it permits today's linked visit with a new daily token and retained reservation.
- [x] Call an eligible entry into one counter; another counter cannot occupy that same entry.
- [ ] Recall the entry and observe an event although its status remains `CALLED`.
- [x] Start and complete service through JavaFX; the shared API and history agree on the completed record.
- [ ] Skip a called entry; it remains unresolved until returned or terminally resolved.
- [ ] Return a skipped entry; its identity/history remain and its fresh ready sequence places it at the walk-in tail.
- [x] Reject an illegal transition or stale entry version without partial changes.
- [ ] Demonstrate permitted cancellation, terminal no-show and interrupted-service abort as distinct outcomes.
- [ ] Demonstrate close/admission cutoff and day rollover without abandoning unresolved records.

## Appointments and queue integration

- [ ] Show availability computed from real calendars and retained reservation capacity.
- [x] Book an available slot; inspect the appointment and its history.
- [x] Reject overlapping non-cancelled future windows for one user across different services.
- [x] Permit adjacent half-open windows whose shared end/start instant is identical, including different services for the same customer.
- [x] Cancel explicitly and verify that the reservation is released.
- [x] Reschedule atomically; when the target is full, the original reservation remains unchanged.
- [x] Check in within configured bounds; exactly one linked queue visit appears in the same committed operation.
- [ ] Reject or safely replay duplicate check-in without another visit or event.
- [ ] Complete the linked visit and observe the appointment become fulfilled.
- [x] Show that fulfillment and no-show retain the slot reservation and do not reopen historical capacity.
- [ ] Verify linked cancellation/abort/no-show behavior and history against the documented state rules.

## Fairness and multiple counters

- [x] Demonstrate FIFO ordering of eligible entries by ready sequence.
- [x] Under appointment-aware policy, show due checked-in appointments ordered by window start then ready sequence.
- [x] With both categories waiting, demonstrate the service-wide appointment streak and a walk-in after two appointment dispatches.
- [ ] Demonstrate that appointment dispatches still increment the capped streak while no walk-in exists.
- [ ] Demonstrate that cancelling after a committed dispatch does not refund the fairness turn.
- [ ] Have two counters request next simultaneously and verify persisted service-wide fairness and distinct occupied entries.
- [ ] Inspect audit history for policy changes and exceptional staff intervention.

## Deliberate contention and failure tests

Use distinct authenticated clients and a shared start barrier. Record responses and final database state.

- [x] Twelve users request tokens concurrently: unique scoped numbers and the expected daily allocation count persist.
- [x] Race eight independent users for the final active place: exactly one visit/token/event/receipt persists, rejected admissions create no receipt, and no participating user has a committed pending receipt.
- [x] The same user submits the same join request UUID concurrently to independent backend processes: one visit and one receipt persist.
- [x] Multiple users compete for the final appointment place: exactly the permitted reservations commit.
- [x] The same user concurrently books overlapping windows across services: exactly one reservation, quota increment, booking event and successful receipt commit; the losing command returns a conflict.
- [x] Call Next races with user cancellation: one legal, internally consistent outcome commits.
- [x] A staff command using a stale entry version after an earlier state change is rejected.
- [x] Race cancellation and requeue by two assigned staff against the same skipped entry/version: one transition/event/receipt commits, the loser is rejected as stale, and the counter remains unoccupied.
- [x] Repeat a successful request key after an ambiguous timeout: same accepted operation, no duplicate business effects.
- [x] In the real browser, discard a committed command's response, observe the pending-request lock, restart the server, sign in again as the original account and recover using the same request: the UI reports "Previously completed request."
- [x] Reuse a key with a different payload: conflict, no replay of unrelated data.
- [x] Cause duplicate receipt insertion: losing transaction rolls back before a fresh committed-result read.
- [x] Inject a failure after runtime revision, audit event, notification work and receipt writes: all those transaction effects roll back together.
- [x] Reject a full-slot reschedule and a check-in blocked by an unresolved visit: the original appointment/reservation remains intact with no partial linked visit.
- [x] Exercise bounded whole-transaction deadlock retry with the same request key; no committed `PENDING` receipt exists.
- [x] A rejected command leaves no committed success receipt.

## Desktop responsiveness and stale data

- [ ] Delay HTTP deliberately and keep the JavaFX controls responsive.
- [ ] Verify at most one refresh per view is outstanding and bounded worker capacity is respected.
- [ ] Switch service/counter or log out during a request; its old response cannot overwrite the current view.
- [ ] Send a command from an old counter claim/session and receive a controlled failure.
- [ ] Display connection failure, last-update time and approximate estimate assumptions clearly.
- [ ] Close the application and verify refresh tasks/executors terminate cleanly.

## Durable notifications and concurrency concepts

- [ ] A business event creates notification work atomically with the business change.
- [ ] Show producer/consumer wake-up signalling and a bounded Java worker pool.
- [x] Run two workers against the same eligible work: recipient inbox items remain unique.
- [x] Inject a failure before worker commit: no partial notification/done state persists; retry completes safely.
- [x] Restart or drop an in-memory hint: the database scanner still finds and completes durable work.
- [x] Mark an inbox item read and verify persistence and recipient ownership.
- [x] Mark an exact snapshot batch read atomically with its receipt; preserve later arrivals, reject foreign/missing IDs without partial changes, and replay concurrent identical requests without a second effect. The bounded batch contains at most 100 IDs.
- [x] Verify no external delivery dependency or lease-expiry assumption is needed for DB-only inbox processing.

## Reports, files and syllabus evidence

- [ ] Display counts and duration measurements calculated from actual stored records, with definitions and sample sizes.
- [x] Reconcile service/date-filtered reports with SQL and display N/A for missing samples.
- [ ] Demonstrate the finite day/hour array matrix and actual `List`, `Map`, `Set` and generic use.
- [x] Verify actual HTTP CSV export quoting, Unicode/newlines, formula-prefix defense, date boundaries and staff scope; desktop transport preserves the exact returned bytes. Native file-chooser rehearsal remains separate.
- [x] Save/reload native serialized non-sensitive preferences; corrupt, excessive or disallowed data is rejected safely.
- [x] Demonstrate a real custom JSP tag on multiple pages, EL and controller-supplied JavaBeans.
- [ ] Explain actual annotations, package structure, inheritance, interfaces, abstraction, overloading/overriding, association, aggregation and composition from executed code.
- [ ] Demonstrate exception propagation/rollback and distinguish checked failures, business failures and JVM errors.
- [ ] Rehearse the JVM/bytecode, wrapper, static initialization, nested/inner class and cleanup explanations. Bytecode inspection and the test-only class-initialization harness have already been executed successfully.
- [ ] Explain `finalize()` as a legacy concept and why neither finalizer execution nor garbage-collection timing is a correctness guarantee.
- [x] Update `syllabus-coverage.md` with real source/test references; label explanation-only evidence honestly.

## Online operation and recovery

- [ ] Browser and JavaFX complete an actual workflow against the same online HTTPS backend.
- [ ] Restart/redeploy the backend and verify persistent bookings, visits, histories, counter occupancy and work records.
- [ ] Confirm MySQL is not exposed to clients and no production secrets are embedded in artifacts/logs.
- [x] Restore a real backup into a separate database and reconcile sample records.
- [ ] Rehearse the documented local fallback independently of the online deployment.
- [ ] Record remaining limitations; remove inactive placeholder controls and unsupported product claims.

## Evidence record

For each checked item record the code revision, environment, action/test command, expected invariant, actual result and relevant row/event/receipt observations. Screenshots supplement the evidence but do not replace data-integrity assertions. A checked item covers the precise tested scenario; broader manual variations still require rehearsal. The tests do not prove public hosting, arbitrary performance scale, or all failure modes.

## Evidence scope and deferred work

- WebIntegrationTest (14 cases): actual JSP compilation/rendering, form registration and role protection, escaping, form JOIN/replay, JSON queue workflow, CSRF/Origin/body limits, stale state, reports/CSV, health, both history routes, notification read ownership, logout/new-login same-key recovery, one-time auth notices, operation-specific feedback and page redirects, appointment quota effects, counter/admin messages, rate limiting, separate workspace content, service-preserving links, invalid-view rejection and direct-page role/service authorization. October 18 adds readable inbox/semantic cues/bulk security, token/reference and terminal-state presentation, and bounded native bulk forms preserving unseen arrivals. The latest focused run completed with successful packaging at 10:05:50 IST on October 18.
- NotificationWorkflowTest (7 cases) and NotificationMessagesTest (5 cases): exact snapshot IDs, recipient validation, stale rows, duplicate-request concurrency/replay, rollback and same-request recovery, late lower-ID commits, the 100-ID limit without unread-row truncation, legacy event presentation and JSON compatibility.
- CommandsDatabaseTest and MultiProcessTest: receipt races, rollback, lost commit acknowledgement, real deadlock/lock timeout, authorization recheck and contention across independent backend JVMs.
- AppointmentWorkflowTest: reservation contention, sequential and concurrent same-customer cross-service overlap protection, exact half-open adjacency, check-in transaction boundaries, multi-counter fairness, retained quota and reschedule rollback.
- HistoryTest: cursor pagination and ownership, persistent visibility of unresolved visits and unread inbox items, and prior-day unresolved visits blocking current-day admission/check-in until resolution.
- BackgroundAndReportsTest: durable worker recovery, exactly one stored inbox result, expiry accounting and measured report values.
- JavaFxSmokeTest, FxTestIsolationTest, RefreshCoordinatorTest, HttpApiClientTest and PreferencesStoreTest provide separate desktop evidence; their fixture and mock boundaries are documented in those tests. Use `scripts/verify-desktop-ui.ps1` for actual native verification, not manual FX environment variables aimed at the shared demonstration server. A native smoke test does not replace inspection of every screen.
- The user deferred actual public hosting. Build/package, configuration validation and local recovery remain current deliverables; the online HTTPS checklist is retained for the later deployment.
- Still rehearse complete administrative/appointment flows, legacy/concept explanations and the full presentation sequence. The live-browser uncertain-request/session-expiry recovery sequence was executed on 2026-10-12. October 16 adds the focused feedback, confirmation, mobile-notification, repeated lost-response and desktop keyboard-sign-out scenarios above. These checks do not constitute a comprehensive accessibility audit or replace the broader checklist.

Historically, the targeted QueueWorkflowTest run at 09:10:06 IST on 2026-10-12 passed all 5 cases, including 2 new contention cases added after the 67-test combined run. The distinct observed count at that point was 69; no 69-test reactor run was claimed.

Recorded live appointment sequence on 2026-10-11: an administrator published a 22:00 local slot; the customer booked it while retaining a walk-in, observed check-in rejection until the walk-in was cancelled, and then checked in to linked visit 6 with persistent notifications visible. Broader appointment variations remain covered by the separate tests/checklist.

The CSV tests added four passing cases at 14:22:56 IST on 2026-10-12, bringing the then-observed total to 73. October 16 evidence superseded that count with 79 distinct passing cases; October 17 added two cases for 81 across separate runs. October 18 reran the reactor with the additional notification/UX cases, then separately passed the isolated native GUI case, for 96 distinct passing tests. See `.local/evidence/ui-database-verification.txt`, historical `ui-*.png` images and the screenshots/logs listed in `verification.md` for the corresponding focused checks. No single zero-skip 96-test reactor run or comprehensive manual UI audit is claimed.
