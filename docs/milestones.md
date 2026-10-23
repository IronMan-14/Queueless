# Implementation and verification milestones

Progress is governed by acceptance evidence, not the previous calendar deadline. The status below reflects recorded local implementation and verification as of 2026-10-18. Current evidence covers 96 distinct passing tests (78 server, 18 desktop): the 10:04:47 IST reactor passed 78 server and 17 desktop cases with the native GUI case intentionally skipped; the isolated native run passed that case at 10:06:35 IST. All 14 HTTP/JSP/MySQL cases also passed again at 10:05:50 IST after the counter JSP correction. This is not a single zero-skip 96-test execution. The initial database-unavailable run failed and was rerun successfully after MySQL was started. Automated evidence does not substitute for an evaluator rehearsal. The user has explicitly deferred selection of hosting and public deployment; a deployable package and deployment instructions remain in scope.

## Working rules

1. Deliver small integrated increments through the real interfaces and database.
2. Preserve visible Servlet/JSP/JDBC/JavaFX implementations; supporting libraries should solve concrete supporting problems.
3. Keep product rules, API contracts, schema constraints and tests consistent when a decision changes.
4. Record meaningful test results and known limitations. Do not label planned functionality as delivered.
5. Keep test fixtures separate from real user data and avoid secrets in source, logs or exported examples.
6. Update the syllabus matrix with actual evidence as the corresponding functionality is verified.

| Gate | Required result | Acceptance evidence | Status |
| --- | --- | --- | --- |
| 1. Reproducible foundation | Maven modules compile; supported runtime and database versions are pinned; environment configuration is documented | Clean build, explicit prerequisite checks, no credentials committed | BUILD + COMBINED VERIFICATION PASSED |
| 2. First integrated slice | A Servlet uses a service and JDBC to read MySQL; JSP renders the result; JavaFX reads the corresponding API | Browser, desktop and SQL inspection agree on the same data; JSON errors are not HTML redirects | LOCAL INTEGRATION VERIFIED (W, F) |
| 3. Authentication and authorization | Registration, login/logout, expiry, three roles and service assignments | Manipulated role/owner inputs, inactive accounts, unassigned staff, expired sessions and CSRF attempts are rejected | IMPLEMENTED; targeted W/C/I/D evidence |
| 4. Services, calendars and counters | Authorized administration configures service availability and counter assignments | Invalid schedules fail; changes persist; conflicting counter/staff claims fail; active work cannot silently disappear | VERIFIED AD (6 tests) + W rendering |
| 5. Transactional walk-in queue | Safe token allocation, admission rules, state transitions and command retries | Real MySQL tests cover uniqueness, active capacity, duplicate membership, rollback, stale versions and replayed commands | TARGETED VERIFIED (Q, D, M) |
| 6. Complete operator workflow | JavaFX operates queues through the backend with counter authorization | Call/recall/start/complete/skip/cancel or approved requeue workflows affect the same browser-visible records; slow HTTP never blocks the FX thread | CORE FX WORKFLOW VERIFIED (F); full rehearsal pending |
| 7. Appointments | Availability, booking/cancellation/history and check-in integrate with queue processing | Last-place race, user overlap, schedule change, check-in window, duplicate check-in and booking-to-queue atomicity checks pass | TARGETED VERIFIED (A, M); complete UI rehearsal pending |
| 8. Queue policies and live status | Defined policies, position and approximate wait calculations work with multiple counters | Policy/fairness scenarios are reproducible; stale results cannot replace a newer selection/session; estimates show sample assumptions and update time | VERIFIED P/A/R/QA; visual rehearsal remains |
| 9. Durable notifications | Relevant events create persistent inbox items through restart-safe background processing | Duplicate workers and recovery preserve unique delivery; readable legacy/current inbox text; exact-snapshot bulk reads validate all recipients and preserve later arrivals; read flags and receipt commit together | VERIFIED (B, W, NotificationWorkflowTest, NotificationMessagesTest); scoped browser observations recorded |
| 10. Reporting and file concepts | Historical reports, genuine calculated metrics, CSV and safe preference serialization | Reports reconcile with source rows, empty samples are explicit, exported fields are correctly escaped, and corrupt/disallowed preference input is rejected | VERIFIED B/S/W/F/QA; live presentation remains |
| 11. Full correctness audit | Cross-feature contention and failure paths satisfy documented invariants | Repeatable competing HTTP requests, injected rollback failures, stale clients, durable ownership takeover, worker crashes and shutdown checks | COMBINED SUITE PASSED; documented test scope applies |
| 12. Deployment and recovery | One HTTPS backend serves both clients; persistent MySQL storage and a local fallback are documented | Online end-to-end run, restart persistence, backup restore, health/log inspection and clean local setup | LOCAL PACKAGE + RESTORE EVIDENCE; public hosting deferred by user |
| 13. Evaluator readiness | Product scope and syllabus claims match implemented behavior | Complete demonstration checklist, actual class/test evidence for syllabus rows, no placeholder controls or unsupported claims | IN PROGRESS: source matrix reconciled; viva/rehearsal pending |
| 14. UI feedback and fixture isolation | The demonstration directory contains intended services; users see progress, confirmation, rejection reasons and safe retry controls | Backed-up fixture-only cleanup, isolated native runner with two guard tests, nine HTTP cases, actual cancellation and mobile notification flows, repeated lost-response recovery and database reconciliation | FOCUSED CHECKS PASSED OCT 16; full accessibility/UI rehearsal remains |
| 15. Separate workspace pages | Overview, Queue & visits, Appointments and Notifications have distinct URLs and only their own content; operator/admin pages remain protected | Eleven HTTP/JSP/MySQL cases passed, including two new page-isolation/access tests; live user navigation, service retention, history return, inbox feedback, legacy link and narrow-view keyboard checks | FOCUSED CHECKS PASSED OCT 17; not a full suite or accessibility rerun |
| 16. UX audit findings | Readable notifications and bounded bulk read; clear tokens, scope and controls; focused summary; useful empty states and compact success feedback | October 18 reactor and isolated native runs cover 96 distinct cases; seven notification transaction cases, five message cases and 14 total HTTP cases; named responsive, service-switch and refresh-confirmation browser checks | AUTOMATED CHECKS PASSED; final browser fault/release evidence recorded separately when completed |

## Definition of done for a feature

A feature is complete only when its user-visible behavior, server authorization, validation, persistence, failure behavior and relevant concurrent behavior are implemented and verified. Its API/UI errors must be understandable; documentation must match reality. An isolated passing unit test does not establish database or deployment correctness.

## Verification layers

- **Domain tests:** transitions, time windows, policy ordering, calculation and validation boundaries.
- **Real MySQL integration tests:** keys, constraints, transaction isolation, lock behavior, rollback and restart durability. An in-memory substitute is insufficient evidence for MySQL-specific behavior.
- **HTTP integration tests:** authentication, authorization, CSRF, DTOs, status codes, idempotency and deliberately competing requests.
- **Desktop checks:** actual login, background work, stale-response rejection, cancellation, logout and clean shutdown.
- **Browser checks:** dynamic JSP output, escaping, forms, polling, session expiry and role boundaries.
- **Operational checks:** production configuration, HTTPS, persistence, backup restoration and reproducible fallback.

Use a barrier to release competing requests together. Assert final database rows, versions, counts, histories and receipts in addition to returned HTTP status codes. Repeat tests only when new changes, failures or unresolved concerns justify it.

## Evidence log format

For each completed gate record the date, code revision, environment, commands or manual steps, actual result, relevant database observations and unresolved limitations. Avoid fabricated dates or results. Recorded local evidence is in `verification.md` and the test-key table in `syllabus-coverage.md`. No public deployment or complete evaluator rehearsal is claimed. October 18 supersedes the earlier 79/81-case totals with 96 distinct passing cases across the reactor and isolated native GUI run. The native case was intentionally skipped in the reactor. The later CSS-only package skipped tests and is build evidence, not another verification run.

## Scope of the current evidence

- W: real embedded Tomcat/JSP/HTTP and MySQL tests, including forms, sessions, queue operations, history, reports, inbox read state and login throttling.
- NotificationWorkflowTest and NotificationMessagesTest: exact-snapshot bulk reads capped at 100 IDs, ownership/rollback/late-arrival protection, same-receipt contention, and readable legacy/current messages with a compatible shared JSON contract.
- F: actual JavaFX controls against the local backend, not a mock-only UI.
- Q/A/B/D/M/P/R/S/C/I: see the precise test classes and latest inspected results in the syllabus evidence table.
- The recovery drill restored the dedicated test database into an isolated MySQL instance and matched 18 table row counts plus receipt, counter and inbox invariants. Its recorded result is under ignored `.local/recovery-093c4873be244e3f895fdd58568b69b3/result.txt`.
- Static-block coverage is a named evaluator-only harness, not fabricated production behavior. Native serialization is restricted to non-sensitive preferences; finalization remains an explanation-only legacy topic.

Combined local suite recorded on 2026-10-12 at 09:08 IST: **67 passed; 0 failures, 0 errors, 0 skipped**. The public-hosting gate is deferred by the user. Customer narrow-view/keyboard and uncertain-request recovery checks passed on 2026-10-12. The full evaluator presentation remains a distinct task. See verification.md for subsequent targeted runs.


Final local evidence as of 2026-10-12 14:22 IST totals 73 distinct passing tests across the full and targeted runs (57 server, 16 desktop), with no failures/errors/skips. A clean production-source build also passed without pre-existing targets, using the local dependency cache. The remaining presentation checklist is retained for the evaluator; Docker execution and public HTTPS are separate deployment gates.

## UI feedback and fixture isolation — verified October 16

The October 16 runs supersede the historical count above. Nine browser/backend integration cases passed again after the final JSP changes, and the WAR packaged successfully. Actual browser checks covered sign-in/out, a rejected duplicate join, cancellation confirmation and backing out, same-section notification refresh, a narrow notification layout, and recovery of a committed action whose response was lost. The final packaged application also issued a fresh token with visible confirmation. Database inspection confirmed a single cancellation event/receipt and retained checked-in appointment quota.

Six proven GUI-test services and twelve matching test accounts were removed from the demonstration database in a backed-up transaction. Future native tests use `scripts/verify-desktop-ui.ps1`, a dedicated test server and `queueless_test`; the demo endpoint is rejected before any fixture creation. See `verification.md` for precise results and screenshots. Broader accessibility testing, the complete evaluator rehearsal and public hosting remain separate gates.

## Separate workspace pages — verified October 17

The 17:27:41 IST follow-up made Overview summary-only, replaced repeated service cards with a compact picker, and kept joining, hours and visit details on Queue & visits. All 11 existing HTTP/JSP/MySQL cases passed again, including new assertions within the existing case for personal/service-wide counts and service selection. Desktop and phone-sized browser checks verified the selector and distinct page content. See `verification.md`; the distinct-test count remains unchanged.

`BrowserServlet` now validates and authorizes separate workspace views, while the shared JSP shell includes only the selected page. Commands, service selection and history/report return links use page URLs; existing known fragment bookmarks migrate to those URLs. Overview counts distinguish customer-owned records from operator service-wide records.

The final focused `WebIntegrationTest` run passed 11 cases with zero failures, errors or skips, and Maven packaging completed at 12:03:32 IST after the final label corrections. Live browser checks covered all four user pages, switching to service 2 while retaining Queue & visits or Appointments, appointment-history return, legacy notification-link migration, and marking an inbox item read while staying on Notifications with confirmation. A configured 390×844 appointment view was inspected (captured image 375×811); keyboard Enter opened Queue & visits and browser Back restored Appointments. Overview navigation was also checked. These are named checks, not an exhaustive accessibility audit or a new full-suite run.

## UX audit findings — verified October 18

Implemented the accepted findings without combining the separate pages again: icons and unread navigation badges, an all-services inbox, grouped explicit service switching, refresh beside the snapshot time, compact success feedback, meaningful notification messages/categories, related history/calendar placement, honest empty states, distinct token/reference labels and explicit terminal next steps. Overview remains a status summary with contextual guidance rather than repeated booking and join forms.

Bulk notification reads submit up to 100 exact IDs from the current snapshot. All requested items must belong to the actor; stale already-read rows are harmless, while any missing/foreign ID rejects the whole batch. Updates and receipt commit together. Later arrivals remain unread even when their ID was allocated before the snapshot. The 100-item cap bounds request/transaction size without discarding unread inbox rows.

The current reactor passed 78 server and 17 desktop tests, then the isolated native JavaFX case passed separately. Fourteen HTTP/JSP/MySQL cases passed again after the final JSP correction. Limited browser checks confirmed the responsive layouts, readable inbox and count, service-preserving navigation, compact feedback, and cancellation of a refresh without losing the edited service choice. See `verification.md` for exact logs, times and subsequent evidence; broad accessibility, evaluator rehearsal and public hosting remain separate acceptance gates.

The final browser fault check also passed: an uncertain bulk-read request survived attempted refresh/navigation and replayed the original result after response delivery recovered. Database inspection confirmed one completed receipt, four marked items and zero remaining unread messages for the browser test account. The final deployed WAR matches the built artifact; the final narrow inbox layout was inspected after the CSS adjustments.
