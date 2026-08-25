# Execution State

- Active writer: `codex-root`
- Active WP: `WP-POC-008` (READY, not started)
- Baseline checkpoint: `53d7002`
- Baseline validation: PASS (checksums, links, JSON/YAML/OpenAPI, DAG, 36/36 endpoint coverage)
- GREEN-API real gate: RUN 2026-08-24 against the deployed VPS instance (`shiftcatcher.servya.com.br`), manually by the operator via direct API calls — not via `scripts/invoke_wp_poc_002_real_probe.ps1`, which was not invoked for this run. See `11-Handoff/WP-POC-002.md` "Real gate evidence" for the recorded chatId/messageId/timestamps and the idempotent-replay/restart-durability observations.
- WP-POC-002 automated transport harness: PASS (adapter, webhook persistence/dedupe, exact quoted send, provider failure modes, concurrency/idempotency, Docker smoke).
- HARD_BLOCKER: resolved. The real GREEN-API instance/credentials, dedicated WhatsApp number, allowed common group, public HTTPS webhook, and visual participant confirmation were provisioned and observed; details in `WP-POC-002.md`.
- `WP-POC-003` promotion: authorized by the project owner on 2026-08-24 after the real-gate evidence above. `WP-POC-004` was authorized the same day and is now the only `READY` writer.
- Windows build path: the physical path contains non-ASCII characters (`Área de Trabalho`) and the Gradle *test* executor cannot load classes from it — every test fails with `ClassNotFoundException` regardless of the task. The `C:\Users\Pedro\.codex\build-paths\shift-catcher-poc-specs` junction does **not** help, because Gradle canonicalizes it back to the physical path. Compilation, ktlint and `validate_spec_package.py` all work in place; to run tests locally, copy the working tree to an ASCII path and run there, or rely on CI, which is the authoritative gate.
- Completed WPs: `WP-POC-001` through `WP-POC-007`, plus `WP-MVP-001`.
- `WP-MVP-001` (first work outside the frozen scope, `12-MVP/MVP-Scope.md`): configurable claim
  wording (`EP-038`, `EP-039`, per-group override via `EP-010`) and the agenda conflict hard rule
  with the `availability` port (`EP-040`-`EP-042`), migration `V10`. The contract goes from 37 to
  42 operations. Neither change moves a `WP-POC-008` verdict: the default wording is still `PEGO`
  and the conflict rule is inert unless a rule set sets `agendaConflictPolicy`, which rule set v1
  in production does not. `RuleEngineTest` 26/26 green locally; the Testcontainers suites
  (`AvailabilityIntegrationTest`, `ClaimMessageIntegrationTest`) were not run locally because no
  Docker daemon was available on the authoring machine — CI is the gate for those.
- `WP-MVP-002`: the operator console at `/console`, server-rendered by the same application with
  Thymeleaf. Sign-in exchanges the `ADMIN_API_TOKEN` for a session (`SameSite=strict`, HttpOnly,
  Secure, 8h) and every state-changing POST carries a per-session CSRF token. It adds **no**
  `/api/v1` operation - it calls the same services in-process - so the contract stays at 42.
  `server.forward-headers-strategy: framework` was added because the container is behind Caddy and a
  redirect rebuilt with the internal http scheme would drop her out of TLS.
  `ConsoleControllerTest` 8/8 green locally (a `@WebMvcTest` slice, no Docker needed): it renders
  every page and asserts that a hostile group message is escaped rather than executed.
- `12-MVP/Calendar-Integration.md`: Google Calendar recorded as a future feature, deliberately
  shaped as a generic calendar service reused by another project. Nothing implemented.
- The second project is now named and its constraints recorded: `12-MVP/Clara-Care-Integration.md`
  (PROPOSED) and `09-Decisions/AUTODEC-0008-Clara-Care-Integration-Boundary.md` (ACTIVE). The
  integration is the **calendar and nothing else** — one busy window crosses, no runtime dependency
  in either direction. Two decisions are urgent rather than tidy: Clara Care titles its calendar
  events with a **patient's name**, so mirroring events as `12-MVP/Calendar-Integration.md` proposed
  would import patient data into this database through a seam built for something else. The read
  side therefore takes **free/busy, not events**, and the mirror **stores windows, not words**. Both
  are free now and archaeology once rows exist. Also decided: an event `source` marker in
  `extendedProperties.private` (without it each system reads the other's writes as unexplained
  conflicts, and its own as a conflict with itself); an architecture test forbidding the calendar
  package from importing this domain, so extraction stays a move rather than a rewrite; this
  project's own `operator_id` rather than Clara Care's `tenant_id`, since the two do not divide the
  world along the same line; and the GREEN-API number never being the clinic's Cloud API line. The
  group claim path cannot migrate to the official API at all — it does not read group messages.
  `12-MVP/Calendar-Integration.md` was amended with the widened port and the mirror rule.
  `WP-MVP-003` (calendar write) and `WP-MVP-004` (calendar read) are `PLANNED`, no endpoints, so the
  contract stays at 42; `MANIFEST.json` and the validator summary go to 12 work packages. No code,
  no contract change, and no `WP-POC-008` verdict moves.
- The Angular successor to the console is specified and bounded: `12-MVP/Frontend-Angular.md`
  (PROPOSED) and `09-Decisions/AUTODEC-0009-Frontend-Embedding-Boundary.md` (ACTIVE), carried by
  `WP-MVP-005` (PLANNED, no endpoints). "An option in the Clara Care menu" is a **link** to this
  module on its own origin — not an iframe (which would force the session cookie to `SameSite=None`
  plus a `frame-ancestors` allowance) and not a federated module (a build dependency, forbidden by
  `AUTODEC-0008` decision 1). The browser talks to a screen-shaped JSON front door under
  `/console/api/*` that reuses the console session and calls the same services in-process, so
  **no operation enters `/api/v1` and the contract stays at 42**; `openapi/poc-openapi.yaml` and
  `06-API/Endpoint-Catalog.md` are unchanged. Every screen element maps to a field that already
  exists or is derivable — no new domain data, no migration. Two `ConsoleSessionFilter` changes are
  the whole of the backend security work: accept the CSRF token from an `X-CSRF-Token` header, and
  answer an unauthenticated `/console/api/*` request with `401` rather than a redirect. `/console`
  keeps serving until the Angular routes pass their gates. `MANIFEST.json` and the validator summary
  go to 13 work packages; `Module-Map.md` gains `webapp`. No code, no contract change, no
  `WP-POC-008` verdict moves.
- Two defects in the existing console surfaced while specifying the port, both in error copy reaching
  the operator: a malformed amount raises `NumberFormatException` from
  `amount.replace(',', '.').toBigDecimal()` and `ConsoleController.act` prints the JDK message raw;
  and `require(current.status.isOpenForAnalysis())` renders the English "Only an opportunity that is
  still open for analysis can be reviewed" to a pt-BR user. `WP-MVP-005` maps both to written pt-BR
  sentences rather than carrying them forward.
- A design control was corrected against the domain rather than the reverse: the review form had
  proposed a "atravessa para o dia seguinte" checkbox and a cross-field error when the end time
  preceded the start. `ShiftOpportunityService.review` **derives** `endsNextDay` as
  `!endTime.isAfter(startTime)`, and `ReviewOpportunityRequest` has no field to carry a checkbox, so
  19:00 → 07:00 is already understood. The form shows the derivation instead of asking. The agenda
  form is the opposite case: `CreateAvailabilityRequest` takes `endsNextDay` explicitly and
  `agenda.html` never rendered the control — there it is real and missing.
- `EP-035`/`EP-036` are now IMPLEMENTED (`benchmark` module, migration `V11`). They had been
  SPECIFIED since the baseline, which meant `WP-POC-008` had no harness and could not have run even
  with a corpus in hand - the earlier note that it "needs corpus and time in the group, not code"
  was incomplete. The replay persists no opportunity and no claim and sends nothing, and it runs on
  its own thread rather than the shared scheduler. It reports facts per criterion and computes **no**
  GO/NO-GO: the three criteria a replay cannot reach (provider-accepted latency, duplicate claims
  under concurrency, visual confirmation of the quote) are listed as `NOT_MEASURABLE_HERE` rather
  than omitted. `BenchmarkScoringTest` 7/7 green locally with the real detector, extractor and rule
  engine; `BenchmarkIntegrationTest` is CI's.
- A benchmark corpus must now declare its provenance (`REAL`, `SYNTHETIC`, `MIXED`), with no
  default, and only `REAL` is admissible as GO evidence. `08-Quality/corpus/synthetic-v1.json` ships
  100 invented messages as a regression floor and NO-GO detector, never as evidence.
- Running that corpus against the real pipeline (AI off, no known locations) on 2026-08-25:
  detection precision 0.87 / recall 0.92; date 32/35; hours 27/35 with **one contradiction**; amount
  13/32 with 19 simply unread; and zero auto-claimable-while-ambiguous, so the `DEC-005` fail-safe
  held across all hundred. Three structural findings are recorded in `08-Quality/corpus/README.md`:
  amounts written without `R$` are not read, offers phrased without the word "plantão" are not
  detected, and `das 7 ate as 19h` was read as different hours. **The parser was deliberately not
  changed**: tuning it to invented text optimises for the corpus author's imagination, and altering
  detection or extraction before `WP-POC-008` runs would move the gate's own baseline.
- That run also exposed a defect in the harness itself, now fixed: `confidentlyWrong` counted an
  unread field as a misreading. Contradiction and absence are separate numbers, because one is a gap
  and the other is a lie. The uncorrected run reported 16 where the truth was 1.
- `WP-POC-008` stays `READY`, not DONE: the harness exists, the run has not happened. It still needs
  the labelled corpus of `08-Quality/Benchmark-Plan.md` (100 messages, >=30 candidates, >=20
  structured, >=10 ambiguous), which the production log does not contain yet.
- Scheduler: `spring.task.scheduling.pool.size` was unset, so Spring's default of **one** thread
  carried all five `@Scheduled` jobs. One of them calls GREEN-API with a 2s connect plus 5s read
  timeout, and the outbox poller - the thing that actually sends the claim - queued behind it. A
  measured send is 179 ms. Now one thread per job (`SCHEDULER_POOL_SIZE`, default 5). Because that
  lets jobs overlap, `ProviderHealthGate.refresh` now allows one live provider call at a time and
  answers the others with the stored observation, or `UNKNOWN` (which blocks) when there is none.
  That race pre-dated the change - an `EP-023` request and the scheduler could already collide - it
  was simply rarer.
- Retention exists (`V13`, `RetentionService`): message content is **redacted in place** and audit,
  spent outbox intents and old benchmark reports are deleted. Nothing had ever been deleted before.
  It runs **dry-run by default** (`RETENTION_DRY_RUN=true`): it counts, logs and audits what it
  would do and changes nothing. Arm it only after reading a pass. Content default is 180 days, the
  longest window on purpose, because the message log is where the real `WP-POC-008` corpus comes from.
- `08-Quality/POC-Acceptance-Test.md` reconciled 2026-08-25 against recorded evidence: 18 of 23 boxes
  are ticked with a citation each. The five open ones are the honest ones - phone-usability over
  days, the benchmark on a real corpus, and any percentile worth the name (production holds one real
  claim).
- Next gate: `WP-POC-008` benchmark and the GO/NO-GO verdict. It needs the corpus from `08-Quality/Benchmark-Plan.md` (100 messages, >=30 candidates, >=20 structured, >=10 ambiguous), which the production log does not contain yet.
- The automatic claim path exists but is disarmed: it needs `shift-catcher.claim.auto-claim-enabled` (defaults false, unset in production), the active rule set's `autoClaimEnabled` (false in v1), and the group flag (off). Any one of them off means nothing is claimed without an explicit `EP-023` call.
- First real claim executed 2026-08-24 via `EP-023`: decided 18:48:45.123Z, provider accepted 18:48:45.463Z (340 ms, inside the 1000 ms SLO), one attempt, no retries, quoting the real offer message.
- Rule set v1 `baseline-conservador` is ACTIVE in production since 2026-08-24; one real opportunity is `ELIGIBLE` and has never been claimed.
- Ingestion observed in production on 2026-08-24: a real group message from the registered group landed with its group resolved. Detection/extraction and the rule engine are deployed but have not been exercised against the live instance yet; no rule set exists there, so every real opportunity would evaluate to `NO_ACTIVE_RULE_SET`.
- Local AI inference is available on the VPS: `garimpo-zap-ollama-1` already runs `qwen2.5:3b`. Measured 2026-08-24 on 2 vCPU: ~55s cold load, ~2.8s warm, ~2GB RAM. A naive prompt misclassified a real offer, so no adapter is wired to `AiShiftParserPort` until a labelled corpus from the real message log exists.
