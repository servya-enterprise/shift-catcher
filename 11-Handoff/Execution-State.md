# Execution State

- Active writer: `codex-root`
- Active WP: `WP-POC-002`
- Baseline checkpoint: `53d7002`
- Baseline validation: PASS (checksums, links, JSON/YAML/OpenAPI, DAG, 36/36 endpoint coverage)
- GREEN-API real gate: NOT RUN; no success is inferred from mocks.
- WP-POC-002 automated transport harness: PASS (adapter, webhook persistence/dedupe, exact quoted send, provider failure modes, concurrency/idempotency, Docker smoke).
- HARD_BLOCKER: the real GREEN-API instance/credentials, dedicated WhatsApp number, allowed common group, public HTTPS webhook, and visual participant confirmation are external inputs and are not available.
- Minimal unblock action: provision those inputs privately, start `scripts/invoke_wp_poc_002_real_probe.ps1` with the exact group `chatId`, and publish its unique source message from another participant; then visually confirm the quote and record `GO` or `NO_GO`.
- Windows build path: execute through `C:\Users\Pedro\.codex\build-paths\shift-catcher-poc-specs` when the physical path contains non-ASCII characters; it is a junction to this same repository.
- Completed WPs: `WP-POC-001`.
- Next gate: real GREEN-API transport proof; credentials are not assumed.
