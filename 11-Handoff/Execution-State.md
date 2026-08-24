# Execution State

- Active writer: `codex-root`
- Active WP: `WP-POC-004`
- Baseline checkpoint: `53d7002`
- Baseline validation: PASS (checksums, links, JSON/YAML/OpenAPI, DAG, 36/36 endpoint coverage)
- GREEN-API real gate: RUN 2026-08-24 against the deployed VPS instance (`shiftcatcher.servya.com.br`), manually by the operator via direct API calls — not via `scripts/invoke_wp_poc_002_real_probe.ps1`, which was not invoked for this run. See `11-Handoff/WP-POC-002.md` "Real gate evidence" for the recorded chatId/messageId/timestamps and the idempotent-replay/restart-durability observations.
- WP-POC-002 automated transport harness: PASS (adapter, webhook persistence/dedupe, exact quoted send, provider failure modes, concurrency/idempotency, Docker smoke).
- HARD_BLOCKER: resolved. The real GREEN-API instance/credentials, dedicated WhatsApp number, allowed common group, public HTTPS webhook, and visual participant confirmation were provisioned and observed; details in `WP-POC-002.md`.
- `WP-POC-003` promotion: authorized by the project owner on 2026-08-24 after the real-gate evidence above. `WP-POC-004` was authorized the same day and is now the only `READY` writer.
- Windows build path: the physical path contains non-ASCII characters (`Área de Trabalho`) and the Gradle *test* executor cannot load classes from it — every test fails with `ClassNotFoundException` regardless of the task. The `C:\Users\Pedro\.codex\build-paths\shift-catcher-poc-specs` junction does **not** help, because Gradle canonicalizes it back to the physical path. Compilation, ktlint and `validate_spec_package.py` all work in place; to run tests locally, copy the working tree to an ASCII path and run there, or rely on CI, which is the authoritative gate.
- Completed WPs: `WP-POC-001`, `WP-POC-002`, `WP-POC-003`.
- Next gate: `WP-POC-005` rule engine, which consumes the `EVALUATING` opportunities the detection stage now produces.
- Ingestion observed in production on 2026-08-24: a real group message from the registered group landed as `PENDING` with its group resolved. Detection/extraction has not run against the live instance yet.
