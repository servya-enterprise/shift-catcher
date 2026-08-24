# WP-POC-007 Handoff

Status: DONE (automated gates). Auto-claim is implemented but has never run in production — it is
switched off by default and stays off until three separate flags are turned on.

## Scope
Reliability and observability: EP-003 (latency percentiles), the provider health gate, the automatic
claim trigger, restart recovery, and the append-only audit trail. The benchmark itself and the
GO/NO-GO verdict are `WP-POC-008`.

## What the stage does
- **Provider health is observed on a schedule and cached** in `provider_health`. This exists because
  of something measured in production: GREEN-API rate-limits `getStateInstance`, and a second call
  moments after a successful one returns `502`. Decisions now read the stored observation; a live
  call happens only when nothing fresh exists. An observation that is **missing, stale or
  non-operational is treated as no answer**, which blocks the automatic path — stale good news is
  never permission to act.
- **The automatic claim trigger** is the only path that claims without a human, and it is
  deliberately hard to arm. All of these must hold: `shift-catcher.claim.auto-claim-enabled` (new,
  defaults `false`), the active rule set's `autoClaimEnabled`, the group's own `autoClaimEnabled`,
  and a stored evaluation that already carries `autoClaimAllowed`. Every refusal is written to the
  audit trail rather than retried in a loop.
- **Restart recovery** falls out of the outbox lease: an event left `PROCESSING` by a crash is
  re-leased once its lease expires, and the interrupted send completes. A lease that is still held is
  left alone, so two workers never send the same message.
- **EP-003** computes P50/P95/P99 in the database for the five metrics of `02-Architecture/Latency-SLO.md`
  plus pipeline counters (webhooks, duplicates, messages, candidates, opportunities, AI fallbacks,
  claims, claimed, failed, attempts, retries). It reports the provider state from the stored
  observation, so reading metrics never consumes the rate-limited quota.
- **`audit_event` is append-only**: the repository exposes only insert and read.

## Automated gate evidence
- GitHub Actions `verify` on `wp-poc-007-reliability`, run `32765761418`: PASS.
- 143 automated tests, 0 failures, against PostgreSQL 18.6 via Testcontainers.
- `python scripts/validate_spec_package.py`: PASS — 55 checksums, 8-WP DAG, 36/36 endpoints.
- Flyway `V7__reliability_and_audit.sql` applied on a clean database in every integration test.

## Tests covering the acceptance criteria
- **restart recovery** — an outbox event left `PROCESSING` with an expired lease and a claim stuck in
  `SENDING` is recovered and completed on the next pass; a lease that has not expired is left alone.
- **provider health blocks auto** — four separate cases: non-operational, stale observation, never
  observed, and an opportunity whose stored evaluation did not set `autoClaimAllowed`. Plus the happy
  path, where every switch is on and exactly one `AUTO` claim is made.
- **latency percentiles** — EP-003 reports samples and percentiles across a full pipeline run, answers
  correctly on an empty database, requires the admin bearer, and does not call the provider.
Also covered: an unreachable provider recorded as `UNKNOWN`/not operational with a failure counter;
a fresh observation reused instead of re-asking; the audit trail recording a blocked pass; and — in
its own context — the safe default, where `auto-claim-enabled` unset means an opportunity every other
switch approves is still never claimed.

## Residual risks
- **Auto-claim has never run in production.** It should not be armed before the operator's real hard
  rules exist in an active rule set; today the production rule set enforces no preference-shaped rule,
  so arming it would auto-claim any structurally complete offer.
- **The health monitor polls the provider every 60 s** whenever the worker is enabled, which is a
  continuous background call against a rate-limited API. The freshness window is 90 s, so the margin
  is one missed poll. If GREEN-API starts rejecting the polls, the automatic path blocks itself,
  which is the intended direction of failure but would be silent without watching EP-003.
- **Delivery remains at-least-once** (`AUTODEC-0007`); this work package narrows the crash window by
  recovering leases, but a crash between provider acceptance and the claim being marked `CLAIMED`
  still results in a re-send.
- EP-003 aggregates over the entire history with no time window, so percentiles drift as the corpus
  grows and cannot show a regression that started recently.
- The audit trail is append-only by construction but nothing prunes it; there is no retention policy.
- `WP-POC-008` still needs the benchmark corpus (100 messages, ≥30 candidates, ≥20 structured, ≥10
  ambiguous per `08-Quality/Benchmark-Plan.md`), which the production message log does not remotely
  contain yet.
