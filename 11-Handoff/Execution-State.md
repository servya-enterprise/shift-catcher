# Execution State

- Active writer: `codex-root`
- Active WP: `WP-POC-007`
- Baseline checkpoint: `53d7002`
- Baseline validation: PASS (checksums, links, JSON/YAML/OpenAPI, DAG, 36/36 endpoint coverage)
- GREEN-API real gate: RUN 2026-08-24 against the deployed VPS instance (`shiftcatcher.servya.com.br`), manually by the operator via direct API calls — not via `scripts/invoke_wp_poc_002_real_probe.ps1`, which was not invoked for this run. See `11-Handoff/WP-POC-002.md` "Real gate evidence" for the recorded chatId/messageId/timestamps and the idempotent-replay/restart-durability observations.
- WP-POC-002 automated transport harness: PASS (adapter, webhook persistence/dedupe, exact quoted send, provider failure modes, concurrency/idempotency, Docker smoke).
- HARD_BLOCKER: resolved. The real GREEN-API instance/credentials, dedicated WhatsApp number, allowed common group, public HTTPS webhook, and visual participant confirmation were provisioned and observed; details in `WP-POC-002.md`.
- `WP-POC-003` promotion: authorized by the project owner on 2026-08-24 after the real-gate evidence above. `WP-POC-004` was authorized the same day and is now the only `READY` writer.
- Windows build path: the physical path contains non-ASCII characters (`Área de Trabalho`) and the Gradle *test* executor cannot load classes from it — every test fails with `ClassNotFoundException` regardless of the task. The `C:\Users\Pedro\.codex\build-paths\shift-catcher-poc-specs` junction does **not** help, because Gradle canonicalizes it back to the physical path. Compilation, ktlint and `validate_spec_package.py` all work in place; to run tests locally, copy the working tree to an ASCII path and run there, or rely on CI, which is the authoritative gate.
- Completed WPs: `WP-POC-001` through `WP-POC-006`.
- Next gate: `WP-POC-008` benchmark and the GO/NO-GO verdict. It needs the corpus from `08-Quality/Benchmark-Plan.md` (100 messages, >=30 candidates, >=20 structured, >=10 ambiguous), which the production log does not contain yet.
- The automatic claim path exists but is disarmed: it needs `shift-catcher.claim.auto-claim-enabled` (defaults false, unset in production), the active rule set's `autoClaimEnabled` (false in v1), and the group flag (off). Any one of them off means nothing is claimed without an explicit `EP-023` call.
- First real claim executed 2026-08-24 via `EP-023`: decided 18:48:45.123Z, provider accepted 18:48:45.463Z (340 ms, inside the 1000 ms SLO), one attempt, no retries, quoting the real offer message.
- Rule set v1 `baseline-conservador` is ACTIVE in production since 2026-08-24; one real opportunity is `ELIGIBLE` and has never been claimed.
- Ingestion observed in production on 2026-08-24: a real group message from the registered group landed with its group resolved. Detection/extraction and the rule engine are deployed but have not been exercised against the live instance yet; no rule set exists there, so every real opportunity would evaluate to `NO_ACTIVE_RULE_SET`.
- Local AI inference is available on the VPS: `garimpo-zap-ollama-1` already runs `qwen2.5:3b`. Measured 2026-08-24 on 2 vCPU: ~55s cold load, ~2.8s warm, ~2GB RAM. A naive prompt misclassified a real offer, so no adapter is wired to `AiShiftParserPort` until a labelled corpus from the real message log exists.
