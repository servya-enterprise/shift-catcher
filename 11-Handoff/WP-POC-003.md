# WP-POC-003 Handoff

Status: DONE (automated gates); no production observation is claimed yet — see "Residual risks".

## Scope
Ingestion and the group allowlist: EP-007..EP-014 (allowlist) and EP-015..EP-017 (normalized
message log). Detection, extraction, rules and claims stay out of scope and remain `SPECIFIED`.

## What the stage does
- Every accepted group text webhook writes two rows: the raw `incoming_provider_event` (unchanged
  capture from `WP-POC-002`, now carrying `payload_hash`, `correlation_id`, `processing_status`,
  `ignored_reason`) and a normalized `incoming_message` (trimmed, whitespace runs collapsed).
- The allowlist gate then sets the event status: `PENDING` when the group is registered and
  enabled, `IGNORED` with `GROUP_NOT_ALLOWLISTED` or `GROUP_DISABLED` otherwise. `PROCESSED` is
  deliberately never set here; it belongs to the detection stage in `WP-POC-004`.
- Direct chats and non-text messages are acknowledged with `200`/`IGNORED` and nothing about them
  is persisted, so the provider stops redelivering payloads this instance will never act on.
  Malformed payloads still fail as `400 application/problem+json`.
- EP-017 re-applies the gate to a stored message, which is what makes registering a group after
  its messages arrived recoverable without asking the provider to redeliver anything.
- Allowlist edits (EP-010) require the current `version` and answer `STALE_VERSION` on mismatch.
  A request that changes nothing does not consume a version, so repeating a toggle is idempotent.
  `autoClaimEnabled` starts `false` and is never set implicitly (`DEC-005`).

## Automated gate evidence
- GitHub Actions `verify` on `wp-poc-003-ingestion-allowlist`, run `32682220402`: PASS.
- `./gradlew verify --no-daemon`: BUILD SUCCESSFUL — ktlint (main, test, scripts), compilation,
  `validateSpecs`, and the full test task.
- 44 automated tests executed, 0 failures: the 26 inherited from `WP-POC-001`/`WP-POC-002` plus 18
  new ingestion/allowlist tests, all against PostgreSQL 18.6 via Testcontainers.
- `python scripts/validate_spec_package.py`: PASS — 55 checksums, 8-WP DAG, 36/36 endpoints.
- Flyway `V3__ingestion_and_allowlist.sql` applied on a clean database in every integration test.

## Tests covering this work package
Allowlist: registration defaults, duplicate chat is `CONFLICT`, non-group chat is
`INVALID_REQUEST`, unknown id is `RESOURCE_NOT_FOUND`, `PATCH` version handshake including
`STALE_VERSION`, toggle idempotence without version inflation, explicit-only auto-claim, and
missing admin bearer.
Ingestion: unregistered group stored but not queued, allowlisted group queued as `PENDING`,
disabled group ignored with its own reason, direct chat and non-text acknowledged without
persistence, redelivered webhook not duplicated in the message log, whitespace normalization with
the raw payload left intact, payload hash and correlation id recorded, reprocess promoting a
message after late registration and then being a no-op, reprocess of an unknown id, and the
message log/detail projection.

## Migration note
`V3` backfills the rows captured during the `WP-POC-002` real gate into `incoming_message` and
marks their events `GROUP_NOT_ALLOWLISTED`, which is the state the new gate would produce for
them: the allowlist starts empty. Registering the real group and calling EP-017 promotes them to
`PENDING`. No column was dropped and no row was deleted.

## Residual risks
- The stage has passed its automated gates but has **not** been observed against the live
  instance yet. Until the real group is registered through EP-008 in production, every incoming
  message there will keep landing as `IGNORED`/`GROUP_NOT_ALLOWLISTED` — which is the intended
  fail-safe, not a defect, but it means nothing is queued for the next stage until that call is
  made.
- `PENDING` messages accumulate with no consumer until `WP-POC-004` adds detection.
- EP-015 returns the 100 most recent messages with no paging or filtering. The frozen contract
  declares no query parameters for it, so the cap is deliberate rather than extensible here; a
  larger corpus will need the contract widened first.
- The message log keeps text from groups that are not allowlisted, so EP-017 can promote them
  later. `AUTODEC-0004` records why and how to reverse that if a stricter policy is wanted.
- EP-002 now reports `INGESTION_IN_PROGRESS`/`WP-POC-003`/`VERIFIED`; those values are hardcoded
  and must be revisited whenever the active work package changes.
