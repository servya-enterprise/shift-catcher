# WP-POC-002 Handoff

Status: IN_PROGRESS — HARD_BLOCKER: REAL_GATE_INPUTS_UNAVAILABLE

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

## Real gate still required
- Developer instance connected to a dedicated real WhatsApp number.
- Public HTTPS Webhook Endpoint configured with separate Bearer token.
- Message from another participant in an allowed common group.
- Visual confirmation that `PEGO` appears in the same group quoting the exact source message.
- Repeat/retry and backend restart observations.

## Credential state at bootstrap
No `.env` and none of `GREEN_API_API_URL`, `GREEN_API_INSTANCE_ID`, `GREEN_API_API_TOKEN`, or `GREEN_API_WEBHOOK_TOKEN` were present. No real credential has been requested, printed, logged, or committed.

## HARD_BLOCKER evidence
- Current WP: `WP-POC-002`; it remains the only `READY` writer.
- The external gate has not run because a connected GREEN-API Developer instance, dedicated real WhatsApp number, allowed common group, public HTTPS callback, and participant visual observation are unavailable to this execution.
- Exhausted in-scope alternatives: real adapter, fake provider, PostgreSQL persistence/dedupe, concurrency/idempotency tests, provider failure modes, environment template, Docker image, and local Compose runtime are complete.
- Code, mock, local configuration, or `AUTODEC` cannot truthfully prove delivery to a real WhatsApp group or that WhatsApp rendered the exact quote. GREEN-API itself documents that HTTP acceptance alone is insufficient evidence of rendered delivery.
- Minimal human action: privately provision the four `GREEN_API_*` values, expose/configure the HTTPS webhook, connect the dedicated number, add it to an allowed ordinary group with another participant, start the probe runner, and publish the prompted source message. The runner executes and records the API/replay portion; a participant must record whether `PEGO` visibly quotes that exact message.
- WP-POC-003 is intentionally not promoted. No `GO` or `NO_GO` result is asserted.

## Gate policy
WP-POC-002 remains `READY` and EP-004/005/006/033 remain `IMPLEMENTED`, not `VERIFIED`, until the real gate is evidenced. WP-POC-003 must not be promoted before that result.
