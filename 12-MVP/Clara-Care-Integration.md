# Clara Care Integration

Status: **PROPOSED — not built.** No code in this repository implements, imports or calls anything
belonging to Clara Care, and none is authorised by this document.

Like `12-MVP/MVP-Scope.md` and `12-MVP/Calendar-Integration.md`, this sits outside the frozen POC
scope. `01-Product/Non-Goals.md` lists "integração com Clara Care" among the POC non-goals and that
stays true. What follows records the *shape* of a later integration, because several of the
decisions in it are cheap today and expensive once the calendar adapter exists.

## The other project

Clara Care V1 is a multi-tenant SaaS for private home-visit medical care: acquisition, triage and
anamnesis, clinical record, medication, care plan, a patient portal, scheduling and billing. It is
ahead of this project in code — twenty-eight migrations, WhatsApp Cloud API connection, inbox,
portal identity — and frozen at a different altitude: `tenant_id` on every clinical table with row
level security, per-practitioner Google credentials, and a generic webhook ingress that already
names `GOOGLE_CALENDAR` as a provider alongside WhatsApp.

The two systems share exactly one thing, and it is the reason the question comes up at all: **the
same person**. She takes shifts through this system and sees patients through that one, with one
body and one set of hours.

## What integration must mean

One fact needs to cross, and only one: **she is busy**. She cannot see a patient during a shift, and
she cannot take a shift on top of a visit. That fact is bidirectional, it is a time window, and it
carries nothing else.

There is already a medium that both systems speak: **her Google Calendar**. Shift Catcher writes a
claimed shift; Clara Care writes an appointment; each reads the other's as an opaque busy block.
That is the whole integration. It needs no API between the projects, no shared database, no shared
release, and no contract beyond the calendar itself. `12-MVP/Calendar-Integration.md` was already
half-way to this design without knowing it — this document is the other half.

## What must never cross

These are the constraints that make the paragraph above safe rather than merely convenient.

- **No runtime dependency, in either direction.** `02-Architecture/Clara-Care-Reuse-Strategy.md`
  already says it; the calendar design must not become the exception that quietly reintroduces it.
- **No clinical content, ever, in this database.** Not a patient name, not a diagnosis, not an
  address. `01-Product/Non-Goals.md` says "qualquer dado de paciente", and the calendar is the path
  by which that rule would be broken without anybody deciding to break it — see the next section.
- **No shared database, schema, connection or migration history.** Two Postgres instances on one
  host is what production already runs; keep it.
- **Not the same WhatsApp number.** See "Two stacks, forever".
- **Not the same tenancy model.** See "The identity axis".

## The leak the calendar opens

This is the finding that makes the rest of the document urgent rather than tidy.

Clara Care is the source of truth for `Appointment` and writes its appointments into her calendar.
Those events carry a **patient's name in the title**, and often an address. The moment
`CalendarCommitmentSource` mirrors that calendar into a local table — which is exactly what
`12-MVP/Calendar-Integration.md` specifies, for good latency reasons — patient data lands in this
database. Nobody would have decided that. It would simply happen, through a seam designed for
something else, and the first sign of it would be a `SELECT` on the mirror.

Two rules close it, and both are free if they are written before the adapter is:

1. **Read free/busy, not events.** Google's free/busy query returns busy windows with no title, no
   description and no attendees. It is exactly what the agenda conflict rule needs, and it means the
   adapter never receives the words in the first place. Defence by construction rather than by
   discipline: there is no field to forget to strip.
2. **The mirror stores windows, not words.** Start, end, busy, and at most an opaque provenance key.
   If some future need forces the events API — a dedicated calendar she keeps for shifts, say — the
   mirror schema still has nowhere to put a title.

`12-MVP/Calendar-Integration.md` already notes that reading her calendar stores third parties'
appointments, and that a dedicated calendar limits the exposure. It limits the exposure to *her*
private life. It does nothing about the exposure created by the other project writing clinical
appointments into the same place, because that project did not exist in that document.

## Two stacks, forever

The two WhatsApp integrations do not converge, and planning as though they might is the expensive
mistake available here.

- **The group path cannot migrate.** The official WhatsApp Business Cloud API does not read
  arbitrary group messages. There is no compliant migration for the thing this product does;
  `12-MVP/MVP-Scope.md` already records that as a strategic risk, and it also means the Cloud API
  that Clara Care uses is not a future replacement for GREEN-API here.
- **The numbers must be different.** GREEN-API is unofficial automation and WhatsApp bans numbers
  that post automatically. A banned shift-catching number is an afternoon of annoyance; a banned
  clinic number is the clinic's channel to every patient. They must never be the same line, and the
  risk is not halved by putting them on one.
- **Outbound to her is a third thing.** "You got the shift", "an offer needs review" is a message to
  a single consenting recipient — compliant on the Cloud API, cheap per message, and nothing to do
  with the claim path. It belongs behind a `NotificationPort` with no implementation for now, so
  that the question of who sends it stays open and no second messaging stack is built by accident.

## The identity axis

This repository has **no operator in the database**. Eighteen tables, and not one `tenant_id`,
`operator_id` or `practitioner_id`: every row is implicitly hers. That is correct for one user, and
`12-MVP/MVP-Scope.md` already schedules multi-tenancy as the milestone that changes it.

The integration-specific point is that **the two systems do not divide the world along the same
line**. Clara Care's unit is the *tenant* — a practice — with practitioners inside it, and its
multi-tenancy baseline puts `tenant_id` on every clinical table with RLS and composite foreign keys.
This system's unit is *the doctor*. Copying `tenant_id` from Clara Care would copy the wrong axis and
bake a practice-shaped hierarchy into a product that does not have one.

What to decide before the multi-tenancy work package is written, not after:

- an `operator_id` of this system's own, on every operator-scoped table;
- plus a **nullable, opaque external reference** to the Clara Care practitioner — a link, not a
  foreign key, resolvable by nobody but a human — so that an operator can be *associated* with a
  practitioner without importing tenancy, identity or authorisation from the other side.

Retro-fitting an identity axis across eighteen tables is the most expensive change this codebase
could be asked to make, and it gets more expensive per table added.

## What the calendar port must gain

The port proposed in `12-MVP/Calendar-Integration.md` — `listEvents`, `createEvent`, `updateEvent`,
`deleteEvent` — is the shape a shift-claim writer needs. It is not the shape the second consumer
needs, and the second consumer is the entire reason the service is being built generically. The
gaps, each evidenced by something Clara Care already has in code or in its baseline:

- **free/busy**, for the read side and for the rule above;
- **watch channels** — register, renew, stop. Clara Care's webhook ingress already carries a
  `GoogleCalendarChannelRoute` with `resourceId`, token hash and expiry, and a
  `GOOGLE_CALENDAR_NOTIFICATION` event kind. A calendar service without channel registration is one
  it cannot use;
- **incremental sync** via sync token. Re-reading a whole window on a schedule neither scales nor
  notices a deletion;
- **credential lifecycle in the port**: consent, refresh, revoke, and an explicit *this connection is
  broken and needs re-consent* signal. In Clara Care that becomes an attention item; here it has to
  reach the console, because a silently dead calendar makes the conflict rule quietly wrong;
- **`extendedProperties.private` carrying both a correlation key and a `source` marker.** With two
  systems writing into one calendar, and without a source marker, this system sees the shift it
  created as an external commitment conflicting with itself, and Clara Care sees a shift as an
  unexplained external change and raises an attention item. One field prevents both. It is the
  cheapest and most important line in this document;
- **the time zone travels in the event**, not in configuration. This system pins
  `America/Sao_Paulo` in `ShiftCatcherProperties`; Clara Care resolves a zone per tenant and
  practitioner. A shared library that reads a zone from its host's config is not shared.

## The physical form: extractable, not yet extracted

`12-MVP/Calendar-Integration.md` leaves the choice — library, service, or copied package — to when
the second project actually needs it. That deferral is right and this document does not overturn it.
What it adds is the mechanism that makes deferring safe:

**build it here, in a package that cannot import this domain, and enforce that with an architecture
test.** A test that fails when the calendar package references anything under `br.com.shiftcatcher`
outside itself turns a future extraction into a move rather than a rewrite, and it fails on the first
line of leakage instead of at extraction time, when it is too late to be cheap.

For the record, the options and why the recommendation is what it is:

- a **shared Gradle library** couples the two repositories' release cycles, which is the thing
  `02-Architecture/Clara-Care-Reuse-Strategy.md` exists to prevent — a build dependency is a
  dependency;
- a **separate service** adds a deployment, a network hop, and — the real objection — makes one
  process the holder of both products' Google refresh tokens, a worse blast radius than either
  product has today;
- a **copied package** duplicates code and drifts, but drifts slowly for something whose contract
  belongs to Google anyway, and costs nothing until the second consumer exists.

## Hygiene that unblocks the integration

None of this is integration work. All of it is work that integration would otherwise expose.

- **Data classification.** Clara Care classifies data as `PUBLIC` / `INTERNAL` / `PERSONAL` /
  `SENSITIVE_HEALTH` / `SECRET` and keeps the sensitive class out of logs and analytics. This project
  has no classification at all. `incoming_message.content` is `PERSONAL` data belonging to third
  parties who never consented; a Google refresh token is `SECRET` and belongs to her.
- **Retention is not armed.** `RetentionService` exists and runs with `RETENTION_DRY_RUN=true`: it
  has never redacted or deleted anything. A dry run nobody reads is a policy that does not exist.
- **Credentials become data.** `MVP-Scope.md` already identifies this as the centre of gravity for
  multi-tenancy; the calendar brings it forward, because an OAuth refresh token is her credential and
  needs encryption at rest, refresh handling and a revocation path even with one user.
- **Contract conventions**, cheap while endpoints are being touched anyway: `Idempotency-Key`,
  `ETag`/`If-Match`, cursor pagination, and a stable `type` URI namespace for Problem Details. This
  project already matches Clara Care on Problem Details, correlation id, UUIDv7, outbox and
  `/api/v1`; the remaining gaps are small and only get harder once a second client exists.
- **A static admin token and in-memory console sessions** are correct for one operator and one
  container, and are the two things that would have to change first if the two products ever shared a
  sign-in. Nothing here proposes that they should.

## The shared host

Both projects run on the same VPS, and this one already joins the `garimpo-zap_garimpo-internal`
network to reach the shared Ollama. Two shared vCPUs, one inference at a time, roughly 2.8 s warm. If
Clara Care starts using the same model for triage, the two products contend for the same core and
this project's AI fallback — already deliberately behind the fast path — gets slower. That is an
operational decision to make deliberately rather than to discover.

## Where it belongs in the order

`12-MVP/MVP-Scope.md` recommends: close `WP-POC-008`; configurable message and agenda rule (done); a
screen for one operator (done); multi-tenancy. Nothing here changes that order, and nothing here is
code before the gate runs.

What is decidable now, on paper, at no cost: the free/busy read and the mirror schema, the source
marker, the port shape, the extractable-package rule, the identity axis, and the two-numbers rule.
Every one of them gets more expensive the day the calendar adapter is written, and three of them are
irreversible in practice once data exists.

`WP-MVP-003` and `WP-MVP-004` in `10-Roadmap/work-packages.yaml` carry the code. The binding rules
are in `09-Decisions/AUTODEC-0008-Clara-Care-Integration-Boundary.md`.
