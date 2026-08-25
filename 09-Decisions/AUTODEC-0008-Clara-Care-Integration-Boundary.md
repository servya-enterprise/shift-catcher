# AUTODEC-0008 — Clara Care Integration Boundary

## Context
The project owner runs a second product, Clara Care V1: a multi-tenant SaaS for private home-visit
medical care, already well ahead of this one in code. Both are used by the same doctor, both run on
the same VPS, and both need her Google Calendar. `12-MVP/Calendar-Integration.md` was written
knowing a second project would reuse the calendar work, but not knowing what that project is, so it
specified a port shape and a mirroring strategy without the constraints the other side imposes.

`01-Product/Non-Goals.md` puts Clara Care integration outside the POC, `DEC-001` keeps the
repositories separate, and `02-Architecture/Clara-Care-Reuse-Strategy.md` forbids a runtime
dependency between them. None of that is being changed here. What is missing is the shape of the
integration that will eventually be wanted, at the moment when the code that would foreclose it is
about to be written.

## Gap
1. Nothing says **what** may cross between the two systems, so the calendar work would decide it by
   accident.
2. `12-MVP/Calendar-Integration.md` specifies mirroring calendar events into local rows for latency.
   It does not anticipate that the other project writes patient-identifying appointments into the
   same calendar, so following it literally would import patient data into a database whose
   `Non-Goals` forbid it.
3. The proposed `CalendarPort` has four operations and no free/busy, watch channels, incremental
   sync, credential lifecycle or event provenance — none of which the second consumer can do
   without.
4. Nothing pins how a shared calendar distinguishes events each system wrote, so each would read the
   other's writes as unexplained external commitments, and its own as conflicts with itself.
5. This repository has no operator identity at all, and no decision exists about whether the coming
   multi-tenancy work should adopt Clara Care's `tenant_id` axis.
6. Nothing forbids using one WhatsApp number for both products, and the ban risk of the unofficial
   provider is asymmetric between them.
7. `outbox_event.event_type` is constrained to the single value `SEND_CLAIM_MESSAGE`, so the second
   external effect cannot be enqueued at all.

## Alternatives
- **Direct integration** (HTTP API or shared database between the two products). Rejected: it
  contradicts `DEC-001` and `Clara-Care-Reuse-Strategy.md`, and it couples a clinical system to a
  product built on unofficial WhatsApp automation.
- **No integration ever.** Rejected as a plan rather than as a position: the same person cannot be in
  two places, and something will eventually have to know that.
- **Integration through the calendar** — each system writes its own commitments and reads the
  other's as opaque busy windows. Chosen: it is the only option that carries the one fact that must
  cross and nothing else, with no coupling of any kind between the repositories.

## Decision
1. **The calendar is the only channel.** The single fact that crosses is a busy time window. No
   direct call, shared database, shared schema, shared release or build dependency exists in either
   direction.
2. **The read side uses free/busy, not events.** The adapter asks for busy windows, so titles,
   descriptions and attendees never reach this process.
3. **The local mirror stores windows, not words.** Start, end, busy flag and an opaque provenance
   key. The schema has nowhere to put a title, so no future code path can put one there.
4. **Every event this system writes carries `extendedProperties.private` with a correlation key and
   a `source` marker**, and every event read back that is marked as this system's own is ignored as a
   commitment. Clara Care marks its own the same way.
5. **The calendar package may not import this domain**, and an architecture test enforces it. The
   translation `ShiftClaim <-> CalendarEvent` lives outside that package.
6. **The port must expose** free/busy, event CRUD, watch-channel registration/renewal/stop,
   incremental sync by sync token, and credential consent/refresh/revoke with an explicit broken-
   connection signal. Time zone travels in the event, never read from host configuration.
7. **The tenancy axis is this system's own.** Operator-scoped tables get an `operator_id` of this
   project, not Clara Care's `tenant_id`. Association with a Clara Care practitioner, if it ever
   happens, is a nullable opaque reference and never a foreign key.
8. **The GREEN-API number and the Clara Care Cloud API number are never the same line.**
9. **The group claim path never migrates to the official Cloud API**, which cannot read arbitrary
   group messages. Any notification to the operator herself goes behind a `NotificationPort`, left
   unimplemented for now.
10. **`outbox_event.event_type` is widened by migration when the calendar write lands**, with its own
    uniqueness rule per aggregate. A failed calendar write never fails a claim, and the two are never
    one transaction.

## Rationale
Decisions 2 and 3 are the reason this document exists. Everything else here is design; those two are
a leak. Clara Care writes appointments titled with patient names into the same calendar this system
would mirror, so the ordinary, well-motivated implementation of `12-MVP/Calendar-Integration.md`
moves patient data into this database through a seam built for something else. Neither rule costs
anything if written before the adapter; both are archaeology afterwards, because by then the rows
exist.

Decision 4 costs one field and prevents two symmetric bugs that would each look like a data problem
rather than a design one. Decision 5 makes the deferred choice of physical form — library, service or
copied package — safe to keep deferring, which is what `12-MVP/Calendar-Integration.md` wanted and
had no mechanism for. Decision 7 is the expensive one: the two products do not divide the world along
the same line, and eighteen tables is the cheapest this change will ever be.

## Reversibility
MIXED, and worth stating per decision rather than as one word.

- HIGH: 4, 6, 10 — field, port and schema changes with no accumulated data behind them.
- MEDIUM: 1, 5, 8, 9 — reversible while nothing depends on them; each becomes a migration of habit
  rather than of data.
- LOW: 2, 3, 7 — once a mirror holds event bodies, or once tables carry the wrong tenancy column,
  the fix is a data migration and a privacy incident review, not an edit.

The three LOW items are candidates for promotion to `DEC-*` by the project owner. They are recorded
here as AUTODEC because no `DEC` covers the ground and `AGENTS.md` does not permit inventing one.

## Impact
No code changes and no contract changes. `00-Start/POC-Freeze.md` is untouched, the POC non-goal of
Clara Care integration still stands, and no `WP-POC-008` verdict moves — nothing here runs, and
nothing here alters detection, extraction or the rule engine.

`12-MVP/Calendar-Integration.md` gains the port gaps and the mirror rule. `WP-MVP-003` and
`WP-MVP-004` are added to `10-Roadmap/work-packages.yaml` as `PLANNED` with no endpoints; the
contract stays at 42 operations, and any endpoint either package needs is added with that package's
own catalogue, OpenAPI, coverage and checksum update.

## Evidence
- `12-MVP/Clara-Care-Integration.md` — the full reasoning this decision compresses.
- `12-MVP/Calendar-Integration.md` — the port and mirroring strategy being constrained.
- `12-MVP/MVP-Scope.md` — credentials-as-data, the WhatsApp ban risk, and the recommended order.
- `01-Product/Non-Goals.md`, `DEC-001`, `02-Architecture/Clara-Care-Reuse-Strategy.md` — the
  separation this decision preserves rather than relaxes.
- `src/main/resources/db/migration/V6__claim_engine.sql` — the single-valued `event_type` check
  constraint and the partial unique index keyed to it.
- Clara Care baseline: its multi-tenancy, Google Calendar and WhatsApp integration notes, and the
  `GOOGLE_CALENDAR` provider, `GoogleCalendarChannelRoute` and `GOOGLE_CALENDAR_NOTIFICATION` kinds
  already present in its webhook ingress code.

## Status
ACTIVE
