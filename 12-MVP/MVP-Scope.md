# MVP Scope

Status: PROPOSED — this document sits **outside** the frozen POC scope on purpose.

## Why this document exists

`00-Start/POC-Freeze.md` is frozen and lists multi-tenancy, multiple doctors and a frontend as
explicit non-goals, and `PLANS.md` step 10 says the product only evolves after the POC is approved.
The POC's own gate (`WP-POC-008`, benchmark and GO/NO-GO) has **not** run yet.

So this is not an amendment to the freeze and does not authorise ignoring it. It records where the
product is going, so that work done now is done in the right order and the POC's evidence is not
corrupted along the way.

## Who this is for

The first user is one newly-graduated doctor. Her colleagues follow. That sequence — one real user
first, several later — is the single most important constraint here, and it argues against building
for many before the product is good for one.

## What the product must become

1. **A configurable reply.** ~~The claim text is the constant `PEGO`, enforced by a check
   constraint in two tables.~~ **Done (`WP-MVP-001`).** `EP-038`/`EP-039` set the wording, a group
   overrides it through `EP-010`, and the resolved text is frozen onto the claim at decision time.
   The default is still `PEGO` and `transport_test_reply` still refuses anything else, so the POC
   transport proof is untouched.
2. **An availability check.** ~~"I will not take this shift because I already have one that day."~~
   **Done (`WP-MVP-001`).** The `availability` port exists, with two sources: what she records
   through `EP-041` and what this system claimed for her, read live from `shift_claim` so a
   retraction frees the date. The rule is off unless a rule set configures
   `agendaConflictPolicy`, and it compares windows rather than dates by default — two shifts on one
   day that never cross are both hers to take. A third source, her Google Calendar, is elaborated in
   `12-MVP/Calendar-Integration.md`.
3. **Multi-tenancy.** Each doctor with her own WhatsApp number, groups, rules, agenda and history.
4. ~~**A screen.** Everything today is `curl` plus a static admin token.~~ **Done
   (`WP-MVP-002`).** `/console`, rendered on the server by the same application. She signs in once
   with the admin token and the browser then carries only a session, so the credential never reaches
   a page that displays text strangers wrote. It adds no endpoint.
5. **Her calendar.** Writing a claimed shift into Google Calendar, and later reading it back as a
   source of commitments. Deliberately built as a service that speaks calendars rather than shifts,
   because a second project needs the same thing — see `12-MVP/Calendar-Integration.md`.

## The architectural centre of gravity

It is not the screen. It is that **provider credentials move from configuration to data**.

Today one GREEN-API instance is supplied as environment variables. With several doctors, each has
her own instance, her own QR pairing, her own reconnection lifecycle and her own webhook token. That
single change reverberates:

- **Webhook routing** becomes a lookup by `instanceData.idInstance`. The good news is that every
  event already stores that identifier, so the routing key exists in production data today.
- **Webhook authentication** stops being a constant-time compare against one token and becomes a
  per-instance lookup — which must stay fast, because it is on the hot path.
- **Credentials need encryption at rest.** They are WhatsApp session credentials belonging to
  someone else, not our own secret.
- **Rate limits stop compounding.** GREEN-API limits are per instance, so one doctor's traffic no
  longer consumes another's quota. This is one of the few places where multi-tenancy makes things
  easier rather than harder.

## Where the fast path must stay fast

Reaction speed is the product. Measured end to end on 2026-08-24: WhatsApp to webhook 634 ms,
detection 7 ms, evaluation 57 ms, send 179 ms. Everything else was queueing that has since been
removed.

- **The webhook stays O(1) per message.** It persists, parses and returns; it never runs rules, AI
  or sends (`03-Integrations/Webhook-Contract.md`).
- **The deterministic parser stays first.** A well-formed offer is claimed in one to two seconds and
  never reaches the model.
- **The AI stays behind the fast path.** It costs about 7 s on this hardware and only sees messages
  the parser could not read — the ones that are otherwise lost to manual review.

## Claim-first versus verify-first

Inverting the order — claim now, check later, retract if wrong — is only worth its cost for checks
that are **slow**. The agenda conflict check is a local query measured in milliseconds, so it
belongs *before* the claim, as an ordinary hard rule — which is where `WP-MVP-001` put it. Retraction (`EP-037`) exists for the case that
is genuinely slow or uncertain: a reading that came from the model.

Retraction is an exception, not a routine step. WhatsApp leaves a visible "message deleted" mark,
and taking a shift back has a social cost in a small group of colleagues. It is also cosmetic rather
than transactional: if whoever posted the offer already read the claim and assigned the shift,
deleting the message does not undo that agreement.

## Technical decisions that must be made before scale

- **The scheduler is single-threaded.** All background work shares one thread today. With several
  users, one 7 s inference stalls everyone's claims. Pooling and/or partitioning per user is the
  most valuable performance change available, and it is far cheaper to do before multi-tenancy than
  after.
- **The AI does not scale on this VPS.** Two shared vCPUs, serialised inferences, competing with
  another project on the same host. The response cache helps with repeats; the ceiling is hardware.
- **Nothing is pruned.** `audit_event` once wrote 120 rows in fifty seconds. Retention has to exist
  before more users generate more history.
- **One container, no alerting.** A restart is brief downtime the outbox recovers from, but nobody
  is told when the pipeline stops. The console makes this visible rather than solving it: she can
  see that nothing has arrived, which is not the same as being told.
- **Console sessions live in memory.** A deploy signs her out. Acceptable for one operator and one
  container; with several, sessions need somewhere to live.

## Two risks that are strategic, not technical

- **WhatsApp terms of use.** GREEN-API is unofficial automation, and WhatsApp bans numbers that post
  automatically. On your own number that is your own risk, taken knowingly. Onboarding colleagues
  means exposing *their* personal accounts to the same risk. The official WhatsApp Business Cloud
  API does not read arbitrary group messages, so there is no compliant migration path that preserves
  the product concept. This does not invalidate the project; it decides what kind of thing it can be.
- **LGPD.** The system stores messages written by third parties in those groups — people who never
  consented and are not users. For personal use the exposure is small. With several users it needs a
  legal basis, a retention policy and a deletion path.

## Recommended order

1. **Close `WP-POC-008`.** It needs corpus and time in the group, not code, and it is the project's
   own GO/NO-GO. Building on unmeasured accuracy is building on a guess.
2. ~~**Configurable message and the agenda conflict rule.**~~ **Done — `WP-MVP-001`.** Neither
   required multi-tenancy, and neither moves a POC verdict: the default wording is unchanged and the
   new rule does nothing until a rule set asks for it.
3. ~~**A screen for one operator.**~~ **Done — `WP-MVP-002`.** Offers, one-tap claim, manual
   reading, sent replies with retract, the agenda, the message log and the wording setting. Writing
   a claimed shift to Google Calendar still fits here: small, self-contained, immediately felt.
4. **Multi-tenancy.** Only once the product is good for one person, because this step multiplies
   whatever exists — including its faults. Reading her calendar belongs to this milestone rather
   than the previous one, because it needs the same per-user credential machinery.

The ordering is the recommendation. Doing 4 before 2 would scale something that has not yet been
shown to be worth scaling.
