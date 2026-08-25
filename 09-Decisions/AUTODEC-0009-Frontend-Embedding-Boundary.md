# AUTODEC-0009 — Frontend Embedding Boundary

## Context
`WP-MVP-002` shipped the operator console at `/console`: Thymeleaf, server-rendered, calling the same
services in-process, adding no `/api/v1` operation. The project owner now wants that screen rebuilt
in Angular with a proper visual identity, and reached as **an option in the Clara Care menu**.

`AUTODEC-0008` drew the boundary between the two products for *data*: the calendar is the only
channel, one busy window is the only fact that crosses, and no runtime, build or database dependency
exists in either direction. It said nothing about the *user interface*, because no interface was
being proposed at the time. "An option in the Clara Care menu" is precisely the sentence that would
decide that boundary by accident, in whichever way the first commit happened to work.

## Gap
1. "In the Clara Care menu" has three technical readings — a link, an iframe, a federated module —
   and they differ by an architectural decision, not by an implementation detail. Nothing says which.
2. An Angular application cannot call services in-process. It needs HTTP, and nothing says whether
   that HTTP is `/api/v1` (which would put session auth and CSRF on the frozen 42-operation contract)
   or something else.
3. `ConsoleSessionFilter` issues a `SameSite=strict` session cookie. Framed by another origin that
   cookie is simply not sent, so the iframe reading is not a styling choice — it is a cookie policy
   change plus a `frame-ancestors` allowance, and both are security decisions.
4. `12-MVP/Clara-Care-Integration.md` records that the static admin token and in-memory console
   sessions "would have to change first if the two products ever shared a sign-in", and that nothing
   proposes they should. A menu item that looks continuous with Clara Care invites exactly that
   proposal.
5. Server rendering supplied four safety properties for free — no re-submit on reload, a short-lived
   optimistic version, no credential in the document, and no client polling. A SPA supplies none of
   them, and each is a way to send a real WhatsApp message by accident.

## Alternatives
- **Federated module / Angular Elements** loaded into the Clara Care build. Rejected: a build
  dependency is a dependency, which `02-Architecture/Clara-Care-Reuse-Strategy.md` exists to prevent
  and `AUTODEC-0008` decision 1 forbids.
- **Iframe embed.** Not rejected, deferred. It is the only reading that makes the module look
  continuous with the host, and it costs a third-party cookie policy, a `frame-ancestors` allowance
  naming Clara Care's origin in this application's configuration, and the removal of
  `X-Frame-Options`. Real costs, reversible, and worth paying only against a felt need.
- **A menu link** to the module on its own origin. Chosen: it satisfies what was asked at zero
  architectural cost and forecloses nothing.

## Decision
1. **The menu item is a link.** Clara Care's menu navigates to this module on this module's own
   origin. No shared build, no shared bundle, no shared runtime, no shared origin. `AUTODEC-0008`
   decision 1 extends unchanged from data to interface.
2. **The module draws no application chrome.** No brand mark, no header, no footer, no global
   sign-out — the host owns those. It draws its own five-tab internal navigation, which is the
   module's and not the host's.
3. **The browser talks to a front door under `/console/api/*`, never to `/api/v1`.** It reuses the
   console session, calls the same services in-process, and returns screen-shaped payloads. The
   `/api/v1` contract stays at **42 operations** and `openapi/poc-openapi.yaml` is untouched. This is
   the precedent `WP-MVP-002` set — a second entrance onto the same product, not new product — made
   explicit rather than repeated implicitly.
4. **The front door's operations are listed in `12-MVP/Frontend-Angular.md`.** Absent from the
   endpoint catalogue by decision, written down by obligation.
5. **The session cookie stays `SameSite=strict`, `HttpOnly`, `Secure`.** No credential in
   `localStorage`, no admin token in the browser, ever. `ConsoleSessionFilter` gains exactly two
   changes: it accepts the CSRF token from an `X-CSRF-Token` header as well as a request parameter,
   and it answers an unauthenticated `/console/api/*` request with `401` instead of a redirect.
6. **No shared sign-in with Clara Care.** Not now, and not as a side effect of the menu item. If it
   is ever wanted it is its own decision, with its own document, and it starts by replacing the
   static admin token.
7. **`[innerHTML]` is banned in the module by lint rule.** The console's safety property was that
   the safe thing is the default thing; Angular interpolation preserves it and the ban keeps it from
   being opted out of on a busy afternoon.
8. **The four SPA-only risks are gates, not notes.** A double click produces one claim; a stale
   `version` repaints from the server rather than retrying; polling reads the database and never
   GREEN-API; a hostile group message renders as text. Each has a test in `WP-MVP-005`.
9. **`/console` keeps serving until the Angular routes pass those gates**, then becomes a redirect.
   The Thymeleaf console is the only screen the operator has, and deleting it before its replacement
   works costs her the product.

## Rationale
Decision 3 is the load-bearing one. Widening `/api/v1` to accept a browser session would put session
authentication and CSRF on the same surface that carries the admin token, on the same contract the
POC froze at 42 operations, in order to serve one screen — and it would make the browser assemble
screens out of resource calls, downloading every third-party message to render the quoted line on one
card. A screen-shaped front door costs one controller and keeps both the contract and the message log
where they are.

Decision 1 is cheap now and expensive later in a way that is easy to miss: the iframe reading is not
reversed by deleting an `<iframe>` tag. It is reversed by walking back a cookie policy, a CSP
allowance and whatever came to depend on the module rendering inside another origin. Starting with a
link keeps the iframe available as an upgrade; starting with an iframe does not keep the link.

Decision 8 exists because the four properties being lost were never written down as properties. They
were consequences of Thymeleaf. A reader of the new code would find no trace of them, and the first
symptom of losing one is a duplicate `PEGO` in a group of colleagues.

## Reversibility
- HIGH: 2, 4, 7 — chrome, a document, a lint rule.
- MEDIUM: 1, 3, 5, 9 — each becomes a migration of habit and a controller, not of data. Decision 1
  upgrades to an iframe by changing cookie and CSP policy deliberately.
- LOW: 6 — a shared sign-in, once operators exist on both sides, is an identity migration and an
  authorisation review, not an edit.

## Impact
No code changes and no contract changes from this document. `00-Start/POC-Freeze.md` is untouched,
the POC non-goal of a frontend still stands, and no `WP-POC-008` verdict moves — nothing here runs,
and nothing here alters detection, extraction, the rule engine or the claim path.

`WP-MVP-005` is added to `10-Roadmap/work-packages.yaml` as `PLANNED` with no endpoints; the contract
stays at 42 operations, `06-API/Endpoint-Catalog.md` and `openapi/poc-openapi.yaml` are unchanged.
`MANIFEST.json` and the validator summary go to 13 work packages. `02-Architecture/Module-Map.md`
gains the `webapp` module.

## Evidence
- `12-MVP/Frontend-Angular.md` — the full specification this decision bounds.
- `src/main/kotlin/br/com/shiftcatcher/console/ConsoleSessionFilter.kt` — the `SameSite=strict`
  session, the CSRF-by-request-parameter check, and the redirect-on-unauthenticated behaviour that
  decision 5 changes.
- `src/main/kotlin/br/com/shiftcatcher/console/ConsoleController.kt` — the in-process call pattern
  the front door reuses, and the `POST/redirect/GET` that decision 8 replaces.
- `src/main/kotlin/br/com/shiftcatcher/shift/ShiftOpportunityService.kt` — `review` derives
  `endsNextDay`, which is why the designed checkbox was removed rather than the service extended.
- `06-API/Endpoint-Catalog.md` — the 42-operation baseline and the existing note that `/console`
  deliberately adds none.
- `09-Decisions/AUTODEC-0008-Clara-Care-Integration-Boundary.md` — the data boundary this extends to
  the interface.
- `12-MVP/Clara-Care-Integration.md` — the admin token and console sessions as the two things a
  shared sign-in would have to change first.

## Status
ACTIVE
