# AUTODEC-0006 — Rule Outcome Semantics

## Context
`WP-POC-005` implements `04-Domain/Rule-Engine.md`: versioned rule sets (EP-027..EP-032) and the
evaluation that moves an opportunity out of `EVALUATING` (EP-021). The spec fixes the three results
(`ELIGIBLE`, `REJECTED`, `REVIEW_REQUIRED`), the list of configurable hard rules, the immutability of
an active rule set, and that an error evaluating a hard rule yields `REVIEW_REQUIRED`.

## Gap
1. The spec does not say **which** rule violations produce `REJECTED` versus `REVIEW_REQUIRED`.
2. It does not say what happens when **no rule set is active**.
3. `EP-022 ignore` already marks an opportunity `REJECTED`; whether a later evaluation may move it
   again was undefined.
4. `EP-032 simulate` must be "sem efeito", which could mean "does not change the opportunity" or
   "leaves no trace at all".
5. `02-Architecture/Module-Map.md` draws `shift -> rules`, but evaluation has to read the aggregate
   it judges, plus the group and the source message.

## Decision
- **`REJECTED` means "definitively not what the operator asked for"**: wrong weekday, start time
  outside the window, duration above maximum, amount below minimum, city not allowed, blocked
  location, stale message, disabled group. **`REVIEW_REQUIRED` means "we are not certain enough to
  say"**: ambiguous extraction, missing required field, confidence below minimum, provider state
  unknown or non-operational. When both apply, `REJECTED` wins, but every reason is recorded.
  Notably, low confidence and a non-operational provider are *not* rejections: they are statements
  about our own certainty or a transient condition, and discarding a real offer over either would be
  the wrong failure.
- **No active rule set yields `REVIEW_REQUIRED`** with `NO_ACTIVE_RULE_SET`. An unconfigured policy
  is not an approval.
- **A manually ignored opportunity is never re-opened by evaluation.** `EP-021` refuses with
  `CONFLICT` when the opportunity is `REJECTED` with `MANUALLY_IGNORED`, or is already claimed,
  claim-pending, claim-failed or expired. A rule-rejected opportunity *can* be re-evaluated, so
  fixing a rule set can rescue it.
- **A simulation persists nothing at all** — no `rule_evaluation` row, no status change — and echoes
  each opportunity's stored status alongside the hypothetical result. That is the reading of "sem
  efeito" that cannot be misread later.
- **The rules module reads `shift`, `group` and `messaging`**, inverting the map's `shift -> rules`
  arrow. The policy lives in one place instead of being scattered across the modules it judges, and
  the alternative would have created a cycle. `EP-021` is therefore served by a controller in
  `rules` even though its path sits under `/opportunities`.
- **Auto-claim requires both switches**: the rule set's global `autoClaimEnabled` and the group's own
  flag, on top of an `ELIGIBLE` result (`DEC-005`). The reasons list records which switch was off, so
  "why did this not auto-claim" is always answerable.

## Rationale
The expensive failure in this system is claiming a shift the operator did not want, and the second
most expensive is silently discarding one they did. Splitting the negative outcomes along
"unwanted" versus "uncertain" maps those two failures onto different states: `REJECTED` is safe to
automate, `REVIEW_REQUIRED` always ends with a human. Storing the rule set version on every
evaluation is what keeps a verdict explainable after the policy that produced it has changed.

## Reversibility
HIGH. The outcome mapping is one function in `RuleEngine`; the reason codes are already recorded
per evaluation, so re-classifying any of them later is a code change plus a re-evaluation, with the
history intact.

## Impact
`shift_opportunity.status` now reaches `ELIGIBLE` and `REJECTED` through evaluation. `EP-022`'s
`MANUALLY_IGNORED` gains a second meaning: it is also the marker that blocks re-evaluation. No
`DEC-*` altered, no change to the frozen scope or the endpoint catalogue.

## Evidence
- `04-Domain/Rule-Engine.md` for the three results and the fail-safe on evaluation error.
- `09-Decisions/DEC-005-Fail-Safe-Auto-Claim.md` for the auto-claim conditions.
- `03-Integrations/Webhook-Contract.md` for why evaluation is never inline with ingestion.
- `05-Data/Data-Model.md` for `rule_set` / `rule_evaluation`.

## Status
ACTIVE
