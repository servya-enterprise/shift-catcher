# Execution State

- Active writer: `codex-root`
- Active WP: `WP-POC-003`
- Baseline checkpoint: `53d7002`
- Baseline validation: PASS (checksums, links, JSON/YAML/OpenAPI, DAG, 36/36 endpoint coverage)
- GREEN-API real gate: RUN 2026-08-24 against the deployed VPS instance (`shiftcatcher.servya.com.br`), manually by the operator via direct API calls — not via `scripts/invoke_wp_poc_002_real_probe.ps1`, which was not invoked for this run. See `11-Handoff/WP-POC-002.md` "Real gate evidence" for the recorded chatId/messageId/timestamps and the idempotent-replay/restart-durability observations.
- WP-POC-002 automated transport harness: PASS (adapter, webhook persistence/dedupe, exact quoted send, provider failure modes, concurrency/idempotency, Docker smoke).
- HARD_BLOCKER: resolved. The real GREEN-API instance/credentials, dedicated WhatsApp number, allowed common group, public HTTPS webhook, and visual participant confirmation were provisioned and observed; details in `WP-POC-002.md`.
- `WP-POC-003` promotion: authorized by the project owner on 2026-08-24 after the real-gate evidence above; `WP-POC-002` is now `DONE` and `WP-POC-003` is the only `READY` writer.
- Windows build path: execute through `C:\Users\Pedro\.codex\build-paths\shift-catcher-poc-specs` when the physical path contains non-ASCII characters; it is a junction to this same repository.
- Completed WPs: `WP-POC-001`, `WP-POC-002`.
- Next gate: `WP-POC-004` detection/extraction, which consumes the `PENDING` messages the ingestion gate now produces.
