# WP-POC-004 Handoff

Status: DONE (automated gates); not yet observed against the live instance — see "Residual risks".

## Scope
Detection and extraction: EP-018/019 (opportunity read model), EP-020 (manual review), EP-022
(ignore) and EP-034 (sandbox). Rules, eligibility and claiming stay out of scope; this stage hands
over at `EVALUATING`.

## What the stage does
- **Stage 1 — fast filter.** A weighted, deterministic score over shift keywords
  (`plantao/vaga/cobrir/cobertura/troco/troca/escala`), time ranges, durations, fees and configured
  known locations. A keyword alone, or a time range plus a fee, is enough to be a candidate;
  ordinary conversation is not. Every message that reaches the pipeline gets a `detection_result`
  row with its score and signals, so any decision can be explained afterwards.
- **Stage 2 — deterministic extraction.** Date (`hoje`/`amanha`/`depois de amanha` resolved against
  the message timestamp in `America/Sao_Paulo`, `dd/mm[/yyyy]`, `dia N` never resolving into the
  past), start/end times with overnight detection, bare durations, BRL amounts, and known
  locations/cities matched accent-insensitively. Nothing absent is invented — unread fields stay
  null and are listed in `ambiguous_fields`.
- **Stage 3 — AI fallback.** `AiShiftParserPort` exists with no adapter shipped and
  `shift-catcher.ai.enabled=false`. It is consulted only for a candidate whose essential fields are
  still ambiguous, and never from the webhook request: the ingestion path passes
  `allowAiFallback=false`, `EP-017 reprocess` passes `true`.
- **Outcome.** `EVALUATING` when date, start and end are all resolved (the handover point for
  `WP-POC-005`), `REVIEW_REQUIRED` otherwise, with the reason recorded
  (`ESSENTIAL_FIELD_AMBIGUOUS`, `AI_RESPONSE_INVALID`, `AI_UNAVAILABLE`, `AI_NOT_A_SHIFT_OFFER`).
  The provider event moves `PENDING -> PROCESSED`.

## Automated gate evidence
- GitHub Actions `verify` on `wp-poc-004-detection-extraction`, run `32753562438`: PASS.
- `./gradlew verify --no-daemon`: BUILD SUCCESSFUL — ktlint, compilation, `validateSpecs`, tests.
- 80 automated tests executed, 0 failures: the 44 inherited plus 17 parser unit tests and 19
  pipeline/endpoint integration tests, against PostgreSQL 18.6 via Testcontainers.
- `python scripts/validate_spec_package.py`: PASS — 55 checksums, 8-WP DAG, 36/36 endpoints.
- Flyway `V4__detection_and_opportunity.sql` applied on a clean database in every integration test.

## Tests covering this work package
Fixture corpus for the filter (real-sounding offers are candidates, real-sounding chatter is not,
accents and casing are irrelevant, a time range plus a fee needs no keyword). Extraction units for
relative and explicit dates, late-night timezone edges, overnight ranges, minutes, bare durations,
BRL formats including `1.2k`, the rule that hours are never read as money, and the "invent nothing"
case. Pipeline integration for: complete offer becoming `EVALUATING` without touching the model,
chatter processed with no opportunity, ambiguous offer parked in `REVIEW_REQUIRED`, the model not
being called from a webhook even when enabled, reprocess resolving through the model, invalid model
answer, model failure, `isShiftOffer=false` still going to review, non-allowlisted group never being
parsed, one opportunity per message across replays, review completing/half-completing an
opportunity, review version handshake, ignore being idempotent, a decided opportunity surviving
re-analysis, and the sandbox persisting nothing.

## Residual risks
- **Not yet exercised in production.** The pipeline has only run against fixtures and
  Testcontainers. The real message already stored on the VPS stays `PENDING` after deploy; calling
  `EP-017 reprocess` on it is what will produce the first real opportunity.
- `shift-catcher.detection.known-locations` and `known-cities` are empty in production, so
  `location`/`city` will not be extracted and the `KNOWN_LOCATION` signal never fires until the
  operator configures the places they actually work at.
- The filter is deliberately conservative: a terse offer with no keyword, no fee and no time range
  (for example "24h no HC?") scores below the threshold and is ignored. Widening it trades false
  negatives for false positives that would all land in review anyway.
- `DETECTED` and `PARSING` are never persisted because the pipeline is synchronous
  (`AUTODEC-0005`). If detection later moves to a worker, those states become observable and the
  read model should be revisited.
- No adapter implements `AiShiftParserPort`, so stage 3 is dead code in production until one exists.
  Enabling one also requires moving the call out of any request path that must stay fast.
- EP-018 returns the 100 most recent opportunities with no paging or filtering, matching the
  declared contract; a larger corpus needs the contract widened first.
- EP-002 now reports `DETECTION_IN_PROGRESS`/`WP-POC-004`; those values remain hardcoded.
