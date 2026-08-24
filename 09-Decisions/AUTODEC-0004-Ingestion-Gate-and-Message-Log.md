# AUTODEC-0004 — Ingestion Gate and Message Log

## Context
`WP-POC-003` implements ingestion and the group allowlist (EP-007..EP-017). The frozen specs
fix the entities (`04-Domain/Domain-Model.md`), the tables (`05-Data/Data-Model.md`), the
provider-event lifecycle (`04-Domain/State-Machines.md`) and the webhook request path
(`03-Integrations/Webhook-Contract.md`), but they do not state where the allowlist decision is
recorded, what happens to messages that arrive before their group is registered, or how the
webhook should answer a payload this instance is not supposed to act on.

## Gap
Four reversible questions had to be closed to make the stage implementable:

1. `IncomingProviderEvent` and `IncomingMessage` overlap. `WP-POC-002` had stored the whole
   captured message inside `incoming_provider_event`; the domain model also defines a separate
   normalized `IncomingMessage`.
2. The provider-event lifecycle allows `PENDING`, `PROCESSED`, `IGNORED` and `FAILED`, but not
   which of them ingestion is allowed to set while detection does not exist yet.
3. Messages that arrive from a group that is not yet allowlisted have no defined fate, while
   EP-017 promises an idempotent "reprocess".
4. Non-group chats and non-text messages had been rejected with `400`, which invites the
   provider to redeliver the same unusable payload indefinitely.

## Alternatives
- Move all captured content out of `incoming_provider_event` into `incoming_message` and drop the
  legacy columns; or keep the raw capture and add the normalized projection alongside it.
- Persist nothing for non-allowlisted groups; or persist the message and let the gate mark it.
- Answer non-group/non-text payloads with `400`; or acknowledge them as `IGNORED`.

## Decision
- `incoming_provider_event` keeps the **raw** captured payload fields and gains `payload_hash`,
  `correlation_id`, `processing_status`, `ignored_reason` and `processing_updated_at`.
  `incoming_message` holds the **normalized** projection (whitespace runs collapsed, trimmed) with
  a unique `provider_event_id` and a nullable `group_id`. Raw and normalized text are genuinely
  different values, so keeping both is not duplication, and no destructive column drop is needed
  on a database that already holds real production rows.
- Ingestion may only set `PENDING` (allowlisted and enabled) or `IGNORED` (with
  `GROUP_NOT_ALLOWLISTED` or `GROUP_DISABLED`). `PROCESSED` stays reserved for the detection
  stage that `WP-POC-004` adds, so a later reader can trust that `PENDING` means "waiting for
  detection" rather than "silently finished".
- A group text message is written to the message log even when its group is not allowlisted, and
  the allowlist decision is recorded on the event. This is what makes EP-017 meaningful:
  registering a group afterwards and reprocessing promotes the messages already captured from it,
  without asking the provider to redeliver anything. The provider only forwards the chats the
  operator configured on its side (Developer plan, at most three), so this does not widen what
  the instance listens to.
- Non-group chats and non-text messages are acknowledged with `200` and status `IGNORED`, and
  nothing about them is persisted. Malformed payloads still fail as `400`
  `application/problem+json`, because those are genuine contract violations worth surfacing.
- The allowlist carries `version`; edits through EP-010 require it and answer `STALE_VERSION` on
  mismatch. A request that would not change anything does not consume a version, so repeating a
  toggle stays idempotent. `autoClaimEnabled` defaults to `false` on registration and is never
  set implicitly, per `DEC-005`.

## Rationale
The overriding constraint is that ingestion must stay fast, replay-safe and free of side effects
(`03-Integrations/Webhook-Contract.md` forbids AI, rules and sends inside the request). Recording
the decision instead of discarding the message keeps the stage reversible: every ingestion outcome
can be re-derived from stored state by EP-017 rather than by re-running the provider. Answering
`IGNORED` instead of `400` for out-of-scope payloads removes a retry loop that would otherwise
grow with every direct message or photo the operator receives.

## Reversibility
HIGH. Reverting to a stricter "store nothing before allowlisting" policy is a delete plus a gate
change in `IngestionService`; the normalized table and the status columns stay valid either way.

## Impact
Additive to `WP-POC-002`: EP-004 keeps its response shape and gains `messageId`,
`processingStatus` and `ignoredReason` fields. `IncomingProviderEventRepository` moves from
`integration.greenapi` to `messaging` so the dependency follows `02-Architecture/Module-Map.md`
(`integration.greenapi -> messaging -> group`). No change to `DEC-*`, to the frozen scope, or to
the endpoint catalogue.

## Evidence
- `04-Domain/State-Machines.md` provider-event lifecycle used verbatim for `processing_status`.
- `02-Architecture/Module-Map.md` dependency direction used to place the ingestion service.
- `09-Decisions/DEC-005-Fail-Safe-Auto-Claim.md` for the auto-claim default.
- `V3__ingestion_and_allowlist.sql` backfills the rows captured by `WP-POC-002` into the message
  log and marks them `GROUP_NOT_ALLOWLISTED`, which is the state the new gate would produce for
  them.

## Status
ACTIVE
