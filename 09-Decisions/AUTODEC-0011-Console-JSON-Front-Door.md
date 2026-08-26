# AUTODEC-0011 — The console JSON front door

- **Status:** ACCEPTED
- **Date:** 2026-08-26
- **Scope:** `WP-MVP-005`, backend half
- **Supersedes:** nothing. Refines `AUTODEC-0009` decisions 2, 5 and 12.
- **Frozen decisions touched:** none. `DEC-*` are untouched, `/api/v1` is untouched, the contract is
  still 42 operations and `MANIFEST.json` still says 42.

## Why this exists

`12-MVP/Frontend-Angular.md` specifies the operator module against a front door at
`/console/api/*` that had not one line written. Building the Angular app first, against an
in-memory double, would have produced a module that looks finished and does not authenticate a
`fetch` — and the defect would have surfaced on integration day, which is the day the one real user
was finally going to see something better than what she has.

So the Kotlin came first. This document records what the writing of it settled, including three
things the MVP table got wrong.

## Decisions

### 1. The front door is eighteen operations, not sixteen

`12-MVP/Frontend-Angular.md` names sixteen. Two more exist:

- `GET /console/api/session` — **the most consequential omission in the specification.** The console
  cookie is `HttpOnly`, so the app cannot read the CSRF token from it, and the token was only ever
  issued at sign-in. After a page reload the operator is still authenticated — the cookie is good
  for eight hours — while the app has no token, so every state-changing request answers 403. Her
  only remedy would have been to sign out and back in after each refresh.
- `DELETE /console/api/session` — `AUTODEC-0009` decision 2 says the module draws no global
  sign-out, and that stands for the *menu*. The session still has to be able to die somewhere other
  than by expiry.

### 2. Four changes to `ConsoleSessionFilter`, not two

`WP-MVP-006` anticipated two (accept the CSRF token from a header; answer 401 under
`/console/api/*`). Writing it needed four:

1. **Accept the token from `X-CSRF-Token`, not only from a form parameter.** A `fetch` sending JSON
   cannot set a form field without building a form body.
2. **Answer `application/problem+json` under the API prefix, written by this filter.** Not
   `sendRedirect`: a browser follows a redirect transparently, so the app would receive 200 with a
   sign-in page in the body and no way to tell that from data. Not `sendError` either:
   `server.error.include-message: never` strips the message, and what comes back may be HTML.
3. **Exempt `POST /console/api/session` from the authentication check.** The existing exemption is
   an exact-URI match on `/console/login`, so a sign-in `fetch` was being redirected before it
   reached a handler. The new exemption is exact on URI *and* method, so `GET` and `DELETE` on the
   same path still require a session.
4. **Check CSRF on every unsafe method.** The original check named `POST`, because the
   server-rendered console only ever posts. The JSON door uses `PUT` and `DELETE`, and a check that
   names one verb protects one verb.

### 3. A second claim is a success, not an error

`ClaimService.claim` answers a duplicate with 409 `CONFLICT`, because a claim already exists. From
where the operator is standing the message went out, and painting the action that worked in red is
the worst available answer — that action is the entire product. The port translates that one 409
into 200 with the existing claim and `alreadyClaimed: true`.

There is no service method that finds a claim by opportunity, so the port scans `ClaimService.list()`,
which is capped at a hundred rows. A miss rethrows rather than inventing a success. Every other 409,
including `OPPORTUNITY_NOT_CLAIMABLE`, passes through untouched: those mean the action will never
work, which is the opposite of what a duplicate means.

### 4. Grouping and formatting happen on the server

The browser receives `go`, `wait` and `closed`, already partitioned, and windows, money and date
eyebrows already rendered in her timezone by `ConsoleFormatter`. A client that receives a flat list
and sorts it owns a second copy of a ten-state machine; a client that reformats an `Instant` with the
browser's locale shows a different hour on a laptop that has travelled.

The card carries a `tone` of six values — `ready`, `attention`, `working`, `sent`, `closed`,
`failed` — rather than a design-system family name. The mapping from tone to badge family lives in
the app. The backend does not import the design system's vocabulary, and the operator is only ever
answering one question per card: is there something here for me to do.

### 5. Blank means "leave it alone", and the conversion happens in the port

`ShiftOpportunityService.review` writes `request.location ?: current.location`. An empty string is
not null, so sending `""` **erases** the location instead of leaving it. The port converts blank to
absent for every optional field, once, rather than asking each form to remember.

There is no field for "crosses into the next day", because the service derives it from the two
times. The screen shows the derivation; it does not ask.

### 6. The amount is read the way a Brazilian writes it

`"1.800,00"` and `"1800"` are both what she types for the same number, and `String.toBigDecimal`
accepts neither of the first. The port normalises; a value that is not a number is a 400
`INVALID_REQUEST`, never a silent zero.

### 7. The connection banner reads the stored observation

`ProviderHealthGate.current()`, never a live probe. This is polled every fifteen seconds from an
open page, and a live probe would spend a rate-limited GREEN-API quota on a screen nobody is
looking at.

### 8. The service ceiling is reported, not hidden

`ShiftOpportunityService`, `IngestionService` and `ClaimService` each cap a list at a hundred rows
with no cursor, no filter and no window. Closed opportunities compete for that ceiling with live
ones. Every list response carries `atCeiling`, and the screen says so. Fixing it properly means
changing the services, which is a new work package and not this one.

### 9. A claimed shift shows no delete button

`CommitmentResponse.reference` is the availability entry's id when the source is `MANUAL` and the
**opportunity's** id when it is `CLAIM`. `AvailabilityService.delete` has never heard of the second,
so a button there would produce a 404 for a row she can see. The row carries `removable`, and it is
false for every `CLAIM`.

## What was verified, and how

`ConsoleApiControllerTest` runs the real filter through MockMvc. It asserts the unauthenticated
`fetch` gets 401 JSON rather than a redirect, that the token never appears in a response, that the
reload path recovers the same CSRF token, that a `DELETE` without the header is refused, that the
board is grouped and formatted, that a duplicate claim is a success and a non-duplicate conflict is
not, that a blank field arrives as `null`, and that a stranger's message arrives whole.
`ConsoleFormatterTest` pins the timezone arithmetic against a fixed clock — the reference instant is
22:30 UTC, which is 19:30 the same evening in São Paulo, so a formatter using the server's day would
be caught.

29 tests pass. `ktlintCheck` is clean.

Note for whoever runs these locally: the working copy lives under a path with a non-ASCII character,
and Gradle's test executor cannot load classes from it. Compilation and ktlint work in place; the
tests were run from a copy at an ASCII path.
