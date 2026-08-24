# WP-POC-006 Handoff

Status: DONE (automated gates); no claim has been sent from the live instance — see "Residual risks".

## Scope
The claim engine and the quoted `PEGO`: EP-023 (claim), EP-024/025 (claims and their attempts),
EP-026 (safe manual retry), plus the transactional outbox and the worker that performs the send.
Recovery after restart, provider health gating of the automatic path, and latency percentiles stay
in `WP-POC-007`.

## What the stage does
- **Deciding a claim is one transaction**: the guarded transition `ELIGIBLE -> CLAIM_PENDING`, the
  claim row, and the outbox intent. It never calls WhatsApp (`DEC-006`).
- **Preconditions** from `04-Domain/Claim-Engine.md`, each with its own error code: an `ELIGIBLE`
  opportunity, an existing source message (`QUOTE_MESSAGE_UNKNOWN`), an enabled group, a stored
  `ELIGIBLE` evaluation, an operational instance (`INSTANCE_NOT_OPERATIONAL`), no existing claim,
  and — for `AUTO` — an evaluation that set `autoClaimAllowed` (`DEC-005`). A refused claim leaves
  the opportunity exactly as it was.
- **Concurrency is resolved by the database**: `shift_claim.opportunity_id` is unique and the
  transition out of `ELIGIBLE` is optimistic, so eight simultaneous claims produce one winner and
  seven conflicts. A partial unique index on the outbox gives one send intent per claim.
- **The quote is frozen at decision time.** Chat id and quoted message id are copied into the claim
  row; the worker never resolves them again.
- **The worker is the only sender.** It leases an event (`for update skip locked`), sends, records a
  `claim_attempt` with latency for every concrete provider call, and completes the claim. Retries
  follow the `0/150/400/800/1500 ms` budget and only for transient failures; a 4xx or an unreadable
  response stops immediately.
- **`EP-026` re-arms the existing intent** rather than creating a second one, so a manual retry can
  never become a second logical message.

## Automated gate evidence
- GitHub Actions `verify` on `wp-poc-006-claim-engine`, run `32763429693`: PASS on the first attempt.
- `./gradlew verify --no-daemon`: BUILD SUCCESSFUL — ktlint, compilation, `validateSpecs`, tests.
- 15 new claim-engine integration tests, 0 failures, against PostgreSQL 18.6 via Testcontainers.
- `python scripts/validate_spec_package.py`: PASS — 55 checksums, 8-WP DAG, 36/36 endpoints.
- Flyway `V6__claim_engine.sql` applied on a clean database in every integration test.

## Tests covering the acceptance criteria
- **one concurrent winner** — eight threads claim the same opportunity simultaneously: exactly one
  succeeds, one `shift_claim` row, one `outbox_event` row.
- **one logical send** — draining the outbox twice sends once; a manual retry after a failure reuses
  the same claim and the same intent and adds exactly one provider call.
- **quote immutable** — the stored message's `provider_message_id` is tampered with after the claim
  is decided, and the worker still sends the frozen value.
Also covered: the decision writing an intent without sending; the worker completing a claim and
recording an attempt with latency; transient failure exhausting the budget into `FAILED` /
`CLAIM_FAILED`; a 4xx not being retried; a transient failure that clears being claimed on a later
attempt; a claimed claim refusing retry; a non-eligible opportunity refusing a claim; `AUTO` refused
without `autoClaimAllowed`; a non-operational instance blocking before anything is written;
double claim as conflict; listing and detail with attempts; unknown ids.

## Residual risks
- **Nothing has been claimed in production.** The stage is deployed but no `EP-023` call has been
  made against the live instance, so no `PEGO` has ever been sent by the claim engine. The one real
  `ELIGIBLE` opportunity is still untouched.
- **Delivery is at-least-once, not exactly-once.** GREEN-API accepts no idempotency key on
  `sendMessage`; if it accepts a send whose response is lost, a manual retry can produce a second
  WhatsApp message. The outbox guarantees one intent per claim, not one delivery (`AUTODEC-0007`).
- **Nothing triggers `AUTO` automatically.** `EP-023` must be called explicitly, so no message can
  leave without an API call. Wiring the automatic path is `WP-POC-007` and should not be done before
  the operator's real hard rules exist.
- The retry budget is spent inside one worker pass with `Thread.sleep`, which occupies the worker for
  up to ~2.9 s. With a single-threaded scheduler that delays other claims queued at the same moment.
- A crash between the provider accepting and the claim being marked `CLAIMED` leaves the claim in
  `SENDING` with a `PROCESSING` outbox event; the lease expires and the event is retried, which is
  where the at-least-once exposure is widest. Restart recovery is `WP-POC-007`.
- The worker polls every second by default; there is no backoff when the outbox is empty.
