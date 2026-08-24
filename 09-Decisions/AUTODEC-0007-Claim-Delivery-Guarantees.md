# AUTODEC-0007 — Claim Delivery Guarantees

## Context
`WP-POC-006` implements `04-Domain/Claim-Engine.md` and `DEC-006`: the claim transaction, the
outbox, and the worker that sends the quoted `PEGO`. This is the first stage that can put a message
into a real WhatsApp group, so the interesting decisions are all about what cannot happen.

## Gap
1. The spec says two simultaneous claims leave "um vencedor, um 409", but not whether a *repeated*
   claim of an already-claimed opportunity is an idempotent replay or a conflict.
2. It says retries happen "somente erro transitório" without defining which provider failures are
   transient.
3. It does not say whether the retry budget is spent inside one worker pass or across scheduled
   passes.
4. `EP-023` is catalogued as "Claim manual", leaving no endpoint for the `AUTO` mode the engine
   defines.
5. GREEN-API accepts no idempotency key on `sendMessage`, so exactly-once delivery is not available.

## Decision
- **A second claim is a `409`, not a replay.** The claim row is unique per opportunity and the
  transition out of `ELIGIBLE` is guarded, so the second caller is told plainly that it lost. An
  idempotent replay would hide a double-click that the operator may want to know about.
- **Transient means timeout or provider 5xx.** A rejected request (4xx) or an unreadable response is
  permanent and stops immediately: retrying a rejection only repeats the rejection.
- **The retry budget is spent inside one worker pass**, sleeping the configured
  `0/150/400/800/1500 ms` between attempts. The whole budget is under three seconds — shorter than
  the lease — and a shift offer answered later than that is already gone, so spreading it across
  scheduled passes would buy nothing and complicate recovery.
- **`EP-023` accepts an optional `{"mode":"AUTO"}`**, which still requires the stored evaluation to
  have set `autoClaimAllowed`. Nothing triggers `AUTO` automatically in this work package: the
  automatic trigger belongs with the worker in `WP-POC-007`. This keeps the mode implementable and
  testable without silently arming it.
- **The chat and quoted message id are frozen when the claim is decided.** The worker reads them
  from the claim row and never resolves them again, so nothing that happens to the message log
  afterwards can redirect a send.
- **Delivery is at-least-once and this is recorded, not hidden.** If the provider accepts a send but
  the response is lost, a manual retry can produce a second WhatsApp message. The outbox guarantees
  one *intent* per claim; it cannot guarantee one *delivery* against an API with no idempotency key.
- **The scheduler is a separate bean from the processor**, so tests drain the outbox deterministically
  instead of racing a background thread, and the worker can be disabled by configuration.

## Rationale
Every guarantee here is enforced by the database rather than by service-layer discipline: a unique
constraint on `shift_claim.opportunity_id`, a partial unique index on the outbox, an optimistic
transition, and a lease with `for update skip locked`. Code that checks-then-acts would pass the same
tests and still double-send under real concurrency.

## Reversibility
HIGH for the policy choices (conflict-vs-replay, failure classification, retry placement) — each is
a few lines in `ClaimService` or `ClaimOutboxProcessor`. The database constraints are the part worth
keeping regardless.

## Impact
`shift_opportunity` now reaches `CLAIM_PENDING`, `CLAIMED` and `CLAIM_FAILED`. A scheduler runs by
default (`shift-catcher.claim.worker-enabled`), polling the outbox every second; with no claims it
does nothing. No `DEC-*` altered, no change to the frozen scope.

## Evidence
- `04-Domain/Claim-Engine.md` preconditions and transaction order.
- `02-Architecture/Transactionality-and-Idempotency.md` for the retry budget and the claim key.
- `09-Decisions/DEC-006-Transactional-Outbox.md` and `DEC-005-Fail-Safe-Auto-Claim.md`.
- `03-Integrations/Green-API-Contract.md` — no idempotency key on send.

## Status
ACTIVE
