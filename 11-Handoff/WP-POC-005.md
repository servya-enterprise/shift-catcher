# WP-POC-005 Handoff

Status: DONE (automated gates); not yet exercised against the live instance — see "Residual risks".

## Scope
The rule engine: versioned rule sets (EP-027..EP-032) and the evaluation that moves an opportunity
out of `EVALUATING` (EP-021). Claiming and the outbox stay in `WP-POC-006`; this stage hands over at
`ELIGIBLE` and never sends anything.

## What the stage does
- **Versioned, immutable rule sets.** A draft is created with the next version number, may be edited
  while it is a draft, and becomes immutable on activation. Activating a new version supersedes the
  previous one; exactly one may be `ACTIVE`, enforced by a partial unique index rather than by
  service-layer discipline. Reactivating a superseded version is refused — create a new draft.
- **Hard rules from `04-Domain/Rule-Engine.md`**, all optional: `minConfidence`, `allowedWeekdays`,
  `earliestStartTime`/`latestStartTime`, `maxDurationHours`, `minAmount`, `allowedCities`,
  `blockedLocations`, `requiredFields`, `maxMessageAgeMinutes`, `requireOperationalInstance`, plus
  the global `autoClaimEnabled`. A definition that configures nothing rejects nothing. Definitions
  are validated on write, so an impossible rule can never reach activation.
- **Two distinct negative outcomes.** `REJECTED` = definitively unwanted (weekday, window, duration,
  amount, city, blocked location, stale message, disabled group). `REVIEW_REQUIRED` = not certain
  enough (ambiguous extraction, missing required field, low confidence, provider unknown or down).
  Rejection outranks review when both apply, and every reason is recorded. See `AUTODEC-0006`.
- **Fail-safe everywhere.** An exception evaluating any hard rule yields `REVIEW_REQUIRED` with
  `RULE_EVALUATION_FAILED`; no active rule set yields `REVIEW_REQUIRED` with `NO_ACTIVE_RULE_SET`.
- **Auto-claim stays doubly gated**: `ELIGIBLE` plus the rule set's global switch plus the group's
  own flag (`DEC-005`). The evaluation records which switch was off.
- **Versioned verdicts.** Every evaluation stores the rule set version that produced it, so a past
  decision stays explainable after that version is superseded.
- **Simulation with no trace.** EP-032 evaluates a draft against stored opportunities and persists
  nothing — no verdict row, no status change.

## Automated gate evidence
- GitHub Actions `verify` on `wp-poc-005-rule-engine`, run `32759620334`: PASS on the first attempt.
- `./gradlew verify --no-daemon`: BUILD SUCCESSFUL — ktlint, compilation, `validateSpecs`, tests.
- The suite grew from 80 to 109 tests, 0 failures: 14 new engine unit tests and 15 new rule-set /
  evaluation integration tests against PostgreSQL 18.6 via Testcontainers.
- `python scripts/validate_spec_package.py`: PASS — 55 checksums, 8-WP DAG, 36/36 endpoints.
- Flyway `V5__rule_engine.sql` applied on a clean database in every integration test.

## Tests covering this work package
Engine units: empty definition rejects nothing; ambiguous extraction never eligible; missing
required field reviews rather than rejects; low confidence reviews; each preference rule rejects
with its own reason; overnight duration measured across midnight; stale message rejected; disabled
group rejected; provider down/unknown/up; auto-claim needing both switches; an ineligible
opportunity never auto-claimable; an AI extraction with 0.99 confidence still rejected by a hard
rule; rejection outranking review with both reasons kept; an unsupported required field failing safe
to review.
Integration: admin bearer required; draft numbering; invalid definitions refused before activation;
draft editable and active immutable; activation superseding the previous version with only one
active; re-activating the active version as a no-op; no active rule set producing
`NO_ACTIVE_RULE_SET`; a permissive set promoting to `ELIGIBLE`; a hard rule rejecting; an unreachable
provider keeping the offer in review; the verdict keeping the version that produced it after
supersession; a manually ignored opportunity refusing re-evaluation; unknown opportunity 404;
simulation persisting nothing and moving nothing; simulation targeting specific opportunities.

## Production observation (2026-08-24)
Exercised against the live instance right after deploy. Rule set **v1 `baseline-conservador`** was
created, simulated, then activated: `requiredFields = [shiftDate, startTime, endTime]`,
`maxDurationHours = 24`, `requireOperationalInstance = true`, `autoClaimEnabled = false`. Every
preference-shaped rule (`minAmount`, `allowedCities`, `blockedLocations`, `allowedWeekdays`,
`maxMessageAgeMinutes`, `minConfidence`) was deliberately left unset, because those encode the
operator's own preferences and guessing them would be inventing policy.

Against the three real opportunities the pipeline had produced, the verdicts were:
- the complete offer (`2026-08-25`, 19:00→07:00, R$ 1.200, no ambiguity) → **`ELIGIBLE`**,
  `autoClaimAllowed=false`;
- two vague offers ("vaga de amanha as 8h, quem pega?") → **`REVIEW_REQUIRED`** with
  `EXTRACTION_AMBIGUOUS` + `REQUIRED_FIELD_MISSING`.

The simulation predicted exactly those three outcomes before activation and left every opportunity
untouched, which is the first end-to-end confirmation of EP-032 on real data.

## Residual risks
- **GREEN-API rate-limits `getStateInstance`.** Observed in production: a second state call moments
  after a successful one returns `502 GREEN_API_UNAVAILABLE`. A batch simulation now resolves the
  state once, but a run of *individual* `EP-021` calls still makes one call each, and the ones that
  get limited record `INSTANCE_STATE_UNKNOWN` and fail safe to review. Caching the state with a
  short TTL belongs with the worker in `WP-POC-007`; until then, prefer simulating a batch over
  re-evaluating opportunities one by one.
- Evaluation is only ever triggered explicitly (EP-021 or EP-032). Nothing evaluates automatically
  after ingestion, because the webhook contract forbids rules in the request; an out-of-band trigger
  belongs to `WP-POC-007`.
- The active rule set enforces no preference-shaped rule yet, so any structurally complete offer
  from an enabled group becomes `ELIGIBLE`. That is harmless while no claim engine exists, but the
  operator's real thresholds must be set in a v2 draft before `WP-POC-006` can be trusted to act.
- `shift-catcher.detection.known-locations` and `known-cities` are still empty in production, so
  `location`/`city` are never extracted and any city or location rule would evaluate against a null.
- `maxMessageAgeMinutes` is measured against the provider timestamp of the source message. Any clock
  skew on the provider side shifts it directly.
- EP-032 simulates against the 100 most recent opportunities when no ids are supplied, matching the
  cap already used by EP-018.
- The rules module reads `shift`, `group` and `messaging`, inverting the module map's `shift -> rules`
  arrow (`AUTODEC-0006`). Worth revisiting if the map is ever enforced mechanically.
