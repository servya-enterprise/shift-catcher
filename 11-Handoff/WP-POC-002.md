# WP-POC-002 Handoff

Status: IN_PROGRESS — REAL_GATE_EVIDENCED (2026-08-24); WP-POC-003 promotion is a pending human GO/NO_GO decision, not yet asserted.

## Automated scope
- GREEN-API ports and HTTP adapter isolated from domain code.
- `GetStateInstance` normalization including every frozen non-operational state.
- Webhook Bearer authentication, payload-size guard, group-text schema validation, UUIDv7 persistence, and retry dedupe.
- Provider test modes: success, 4xx, 5xx, timeout, and invalid JSON.
- Quoted `PEGO` test reply with immutable chat/message/quote, state gate, persisted idempotency, and one concurrent send winner.
- Idempotent replay is resolved before a new provider health call; key/origin cross-collisions fail closed.
- Verification endpoint never converts HTTP 200/provider `idMessage` into visual quote proof.
- Real-probe runner waits for a post-cutoff webhook from the exact expected group, sends from the persisted IDs, proves HTTP replay, records sanitized latency evidence under ignored `.local-evidence`, and leaves visual confirmation pending.

## Automated gate evidence
- `./scripts/gradle.ps1 clean --no-daemon`, followed by `./scripts/gradle.ps1 verify --no-daemon`: PASS.
- 26 automated tests executed; 0 failures and 0 errors.
- `python scripts/validate_spec_package.py`: PASS — 55 checksums, 8-WP DAG, 36/36 endpoints.
- Docker image build: PASS.
- Docker Compose smoke: PostgreSQL and application healthy; Flyway applied V1/V2.
- Unconfigured runtime probe: `configured=false`, `state=UNCONFIGURED`, `operational=false`.
- Ephemeral Compose containers, network, and volume removed after the smoke test.
- Real-probe runner syntax and fail-closed missing-auth preflight validated without credentials. Its success path remains part of the real gate and is not inferred from a fake orchestration server.

## Real gate evidence (2026-08-24)
All five previously-missing real-gate inputs were provisioned and observed against the live production deployment (`shiftcatcher.servya.com.br`, VPS-hosted per `09-Decisions/AUTODEC-0003-CICD-and-VPS-Deploy.md`):

- **Developer instance connected to a dedicated real WhatsApp number.** GREEN-API instance `710722715723` ("Servya AI"), confirmed via `POST /api/v1/integrations/green-api/verify`: `configured=true`, `providerState=AUTHORIZED`, `providerOperational=true`.
- **Public HTTPS Webhook Endpoint configured with a separate Bearer token.** `https://shiftcatcher.servya.com.br/api/v1/webhooks/green-api`, TLS certificate issued by Let's Encrypt (production), `webhookUrlToken` distinct from `ADMIN_API_TOKEN`, `incomingWebhook` enabled.
- **Message from another participant in an allowed common group.** WhatsApp group "Plantões Medicina" (2 members: the connected instance + the operator's personal number). Operator sent "vaga de amanhã às 8h, quem pega?" from `5514998943823@c.us`; the webhook ingested it and `GET /api/v1/integrations/green-api/verify` returned it as `latestGroupWebhook`: `chatId=120363429389915786@g.us`, `providerMessageId=AC7A8113482BAD3CFA0E93BADAB7FBB6`, `providerTimestamp=2026-08-24T01:01:20Z`, `readyForTestReply=true`.
- **Visual confirmation that `PEGO` appears in the same group quoting the exact source message.** `POST /api/v1/poc/send-test-reply` called with that `chatId`/`quotedMessageId`; operator visually confirmed in WhatsApp that "PEGO" was posted by the connected instance quoting the exact source message.
- **Repeat/retry and backend restart observations.** A second `send-test-reply` call for the same `chatId`+`quotedMessageId` (fresh `Idempotency-Key`) returned `idempotentReplay=true` with no new WhatsApp message sent. The `app` container was then restarted (`docker compose restart app`) and the same call repeated: still `idempotentReplay=true` with the same `providerMessageId`, confirming the reservation is persisted in PostgreSQL, not held only in memory.

Exact JSON payloads for the `send-test-reply` response and the post-restart replay were not captured verbatim in this session's transcript — the outcomes above are as directly observed via the `/verify` calls and as visually reported by the operator running the commands on the VPS. `quotedReplyVisualStatus` remains `NOT_CONFIRMED` in the API's own response (the endpoint never converts HTTP acceptance into visual proof by design, per this WP's automated scope) — the visual proof is the operator's own WhatsApp observation, recorded here, not something the API asserts about itself.

## Credential state at bootstrap
No `.env` and none of `GREEN_API_API_URL`, `GREEN_API_INSTANCE_ID`, `GREEN_API_API_TOKEN`, or `GREEN_API_WEBHOOK_TOKEN` were present. No real credential has been requested, printed, logged, or committed.

## HARD_BLOCKER resolution (2026-08-24)
- The blocker recorded below is historical context for how this WP started; it is resolved as of the evidence in "Real gate evidence" above.
- Original blocker: the external gate could not run because a connected GREEN-API Developer instance, dedicated real WhatsApp number, allowed common group, public HTTPS callback, and participant visual observation were unavailable to this execution.
- Exhausted in-scope alternatives at the time: real adapter, fake provider, PostgreSQL persistence/dedupe, concurrency/idempotency tests, provider failure modes, environment template, Docker image, and local Compose runtime were complete, but code, mock, local configuration, or `AUTODEC` could not truthfully prove delivery to a real WhatsApp group or that WhatsApp rendered the exact quote.
- The minimal human action originally requested (provision the four `GREEN_API_*` values, expose/configure the HTTPS webhook, connect the dedicated number, add it to an allowed ordinary group with another participant, publish the prompted source message, and record whether `PEGO` visibly quotes it) was carried out by the operator; the outcome is recorded above instead of a probe-runner artifact.
- `WP-POC-003` promotion is a separate decision from evidence collection — see Gate policy below. No `GO`/`NO_GO` has been asserted yet.

## Gate policy
The real gate is now evidenced (see above), so EP-004/005/006/033 move from `IMPLEMENTED` to `VERIFIED`. `WP-POC-002` remains `READY` as the current work package. Promoting `WP-POC-003` still requires a separate, explicit `GO`/`NO_GO` decision from the project owner — evidence being collected does not by itself constitute that decision — and that assertion has not been made as of this update.
