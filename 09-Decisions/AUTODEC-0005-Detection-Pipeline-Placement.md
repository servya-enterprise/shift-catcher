# AUTODEC-0005 — Detection Pipeline Placement

## Context
`WP-POC-004` implements the three stages of `04-Domain/Detection-and-Extraction.md` and the
endpoints EP-018/019/020/022/034. The specs fix the stages, the opportunity state machine
(`04-Domain/State-Machines.md`), the AI contract (`07-AI/AI-Parser-Contract.md`) and the fallback
policy, but they do not say *where in the request lifecycle* each stage runs, nor how the endpoints
map onto the state machine.

## Gap
1. `03-Integrations/Webhook-Contract.md` forbids AI, rules and sends inside the webhook request and
   says the request should leave the message "pending processing". Deterministic detection is not
   on that forbidden list and has a P95 target under 100 ms, so its placement was undefined.
2. The opportunity state machine starts at `DETECTED -> PARSING`, but a synchronous pipeline
   resolves both in the same transaction.
3. `EP-022 Ignora` has no matching state: the machine offers `REJECTED`, not `IGNORED`.
4. The fallback policy allows the model to interpret but not to decide; what a "not a shift offer"
   or schema-invalid answer should produce was unspecified.

## Alternatives
- Run detection in a background worker over `PENDING` events; or run it inline in the ingestion
  transaction.
- Persist `DETECTED`/`PARSING` rows and transition them; or create the opportunity already resolved.
- Let the model's `isShiftOffer=false` reject the opportunity; or send it to a human.

## Decision
- **Stages 1 and 2 run inline**, inside the ingestion transaction, and move the provider event from
  `PENDING` to `PROCESSED`. They are pure, deterministic and fast, and running them inline keeps the
  POC free of a scheduler it has no other use for.
- **Stage 3 never runs inside the webhook request.** `AnalyzeMessageCommand.allowAiFallback` is
  `false` on the webhook path and `true` on `EP-017 reprocess`, so the prohibition is enforced by
  the call site rather than by convention. A test asserts the model is not called from a webhook
  even when the adapter is enabled.
- **The opportunity is created in its resolved state** (`EVALUATING` when nothing essential is
  ambiguous, `REVIEW_REQUIRED` otherwise). `DETECTED` and `PARSING` remain declared in the state
  machine but are transient within one transaction and are never persisted. `EVALUATING` is the
  handover point for the rule engine of `WP-POC-005`; this stage never produces `ELIGIBLE`.
- **`EP-022` maps to `REJECTED`** with `resolution_reason = MANUALLY_IGNORED`, rather than adding an
  `IGNORED` state the machine does not define.
- **The model may only fill gaps.** Deterministic values always win on merge; a schema-invalid
  answer, a provider failure, or an `isShiftOffer=false` answer all end in `REVIEW_REQUIRED` with a
  recorded reason, never in a terminal rejection. The model cannot close an opportunity by itself.
- **Re-analysis never overwrites a decision.** `analyze` refuses to touch an opportunity whose
  status is outside `DETECTED/PARSING/REVIEW_REQUIRED/EVALUATING`, so reprocessing after a human
  reviewed or ignored it is a no-op.
- **Amounts are only read when the text marks them as money** (`R$`, `1.2k`, `... reais`). A bare
  number in a shift message is far more often an hour than a fee, and `19-07` must never become
  `R$ 19`.
- **`/` is not a time-range separator**, so `19/07` stays a date.

## Rationale
The failure that matters here is a wrong automatic claim, so every ambiguous branch resolves toward
a human. Enforcing the AI prohibition through an explicit parameter (rather than a comment) means a
future adapter cannot accidentally be wired into the request path. Collapsing `DETECTED`/`PARSING`
is honest about what a synchronous pipeline actually does instead of writing rows nobody observes.

## Reversibility
HIGH. Moving stages 1-2 to a worker later only changes who calls `MessageAnalysisService.analyze`;
the persistence, the states and the endpoints are unaffected. Enabling a real adapter is a config
flag plus one implementation of `AiShiftParserPort`.

## Impact
`EP-004` gains `candidate` and `opportunityId` in its response, and an allowlisted message now ends
as `PROCESSED` instead of `PENDING`. `EP-017` may now invoke the model. No `DEC-*` is altered and
the frozen scope is unchanged.

## Evidence
- `03-Integrations/Webhook-Contract.md` for the in-request prohibition.
- `07-AI/Fallback-Policy.md` for the three preconditions of a model call.
- `09-Decisions/DEC-004-No-LLM-on-Every-Message.md` and `DEC-005-Fail-Safe-Auto-Claim.md`.
- `02-Architecture/Latency-SLO.md` for the sub-100 ms detection budget that inline execution meets.

## Status
ACTIVE
