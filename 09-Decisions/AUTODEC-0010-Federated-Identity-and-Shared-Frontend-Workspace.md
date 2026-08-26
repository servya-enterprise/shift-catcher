# AUTODEC-0010 — Federated Identity and the Shared Frontend Workspace

## Context
The project owner reframed the products: Shift Catcher is a **feature of Clara Care**, because both feed
the same doctor's agenda. He asked for one repository hosting the frontends, publication under the
`servya.com.br` domain for now, and "um gateway que também vai ter a responsabilidade de login, quero
login via google, quero organização, provavelmente vamos distribuir o projeto entre serviços... sem
gambiarra."

`AUTODEC-0009` decided the opposite of two of those things three days ago: a link rather than an
embed, and a design system copied rather than shared as a build dependency. This document records
what the reframe actually changes, what it does not, and the two things the analysis refused to build.

Four facts were checked on disk rather than assumed, and each one moved a conclusion:

1. **Clara Care is not in production.** No `Dockerfile`, no `docker-compose.prod.yml`, no deploy job —
   only `ci.yml` and a development `infra/compose.yaml`. `claracare.servya.com.br` does not exist. Any
   routing, cookie or SSO design was being drawn on top of an origin nobody had published.
2. **`DEC-ARCH-008` does not need superseding.** Its frozen text is *"Web usa sessão server-side por
   cookie HttpOnly; Angular envia token CSRF separado"* — it fixes the session **mechanism**, not the
   credential presented at login. An earlier reading in this session claimed otherwise and was wrong.
3. **The identity service already exists**, built and frozen inside Clara Care's `identity` module:
   opaque sessions in Spring Session JDBC, Argon2id, TOTP with an AES-256-GCM envelope and a
   rotatable key ring, mandatory invitation, per-IP and per-account rate limiting, an append-only
   security event ledger, session revocation handles, and a `staff_membership_locator` that locates
   without authorising.
4. **Shift Catcher has no operator axis at all** — `grep -rn operator_id src/main/kotlin` returns
   nothing across 18 tables, exactly as `AUTODEC-0008` decision 7 described.

## Gap
1. "One domain" and "Plantões is a feature" together imply one origin, and nothing said whether the
   browser-visible boundary between a WhatsApp-message renderer and a multi-tenant clinical record
   should disappear.
2. A gateway owning login implies a new process holding credentials for both products, and nothing
   said what happens to the session, MFA, invitation and revocation machinery that already exists.
3. Google as an identity provider says who, but neither product's authorisation model says what a
   Google `sub` maps to — Clara Care has membership and RLS, Shift Catcher has one implicit operator.
4. `AUTODEC-0009` decisions 6 and 10 were written for two separate products and no longer describe
   what the owner asked for.

## Alternatives
- **A gateway service owning login** (`servya-id` / `servya-identity`). Rejected. It means writing an
  OIDC provider by hand — on the order of 1.5k lines of credential code, without a second reviewer —
  to guard a clinical database under RLS, duplicating session, MFA, invitation, membership, rate
  limiting and revocation that already exist and are frozen. It also puts a new process in the data
  path of a two-vCPU host that already shares an Ollama, where a claim measures 877 ms end to end. A
  dedicated identity service earns itself when there is a second identity provider, a user who is not
  on Google, or a relying party that cannot hold a client secret. None of those exist.
- **A merged origin** — `claracare.servya.com.br/plantoes`. Rejected on a stronger argument than the
  cookie hygiene raised earlier: it puts the renderer of text written by strangers in a WhatsApp group
  on the **same origin as the clinical record**. An XSS there does a same-origin `fetch('/api/...')`,
  the browser attaches the clinical session cookie, and the response is readable. No CORS and no
  `SameSite` stands in the way. The escape gate in `AUTODEC-0009` decision 8 was calibrated when the
  blast radius was the bot's own console; this would widen it to every record in the active tenant.
- **Federated identity across separate origins.** Chosen: the same Google account signs in to both
  products, each product keeps its own session, its own cookie and its own origin, and no third
  process exists.

## Decision
1. **No new identity or gateway service is built.** Routing stays in Caddy, which already terminates
   TLS and can route by host and path. Caddy never performs authorisation — not a role check, not a
   permission-matched path, not a per-user rate limit.
2. **Google is a login *leg* inside each product**, via `spring-boot-starter-oauth2-client` — the
   audited client half only, never a hand-written provider half. Each product is its own OAuth client
   with its own `client_id`, `client_secret` and allowlist.
3. **Three origins, and every cookie is host-only.** `claracare.servya.com.br` (staff),
   `portal.claracare.servya.com.br` (patient), `plantoes.servya.com.br` (this product). No cookie is
   ever issued for `.servya.com.br`. Realm separation becomes a physical impossibility rather than a
   rule someone has to remember, which is `DEC-ARCH-009` enforced by the browser.
4. **`AUTODEC-0009` decision 1 stands**: the Clara Care menu item is a link to this module on its own
   origin. The reframe changes the product story, not the browser boundary.
5. **`AUTODEC-0009` decision 5 stands, including `SameSite=Strict`.** The claim that an OAuth return
   forces `Lax` on the session cookie is false: `Set-Cookie` works under any `SameSite`, the callback
   is what *creates* the session, and all later traffic is same-site. Only a five-minute transaction
   cookie carrying the PKCE verifier and state needs `Lax`, and it dies at the callback.
6. **Identity is shared; session is not.** `AUTODEC-0009` decision 6 said no shared sign-in with Clara
   Care. That is now partially broken and this is the one substantive break: the same Google account
   signs in to both. There is still **no** shared session, cookie, token, issuer or origin. Decision 6
   itself named the precondition — "it starts by replacing the static admin token" — and that is what
   happens: `ADMIN_API_TOKEN` stops being a person's credential.
7. **`/api/v1` leaves the public internet.** After the Google leg lands, `shiftcatcher.servya.com.br`
   serves `/webhook/*` and nothing else, with its existing bearer check intact. "Only the frontend
   calls it" is an intention; a `respond 404` is a control.
8. **A one-table operator map, not eighteen columns.** `V14__operator.sql` creates `operator(id,
   idp_subject UNIQUE, display_name, created_at)` with one seeded row, and `OperatorContext` is
   threaded through every service that will one day filter by operator. An unknown `sub` is 403.
   **No column is added to the 18 tables yet.** This contradicts the *timing* of `AUTODEC-0008`
   decision 7, not its axis: the axis is still this project's own `operator_id` and never Clara
   Care's `tenant_id`. The trigger to pay is named in decision 12.
9. **Account linking happens once, and never again by email.** A Google `sub` binds against an
   accepted invitation or a session already authenticated the old way, and afterwards the match is by
   `sub`. Email is reassignable by a Workspace admin; `sub` is not. Re-matching by email is the
   classic account-takeover seam. Self-provisioning does not exist: a verified email without an
   accepted invitation is 403.
10. **Google satisfies identity, not the second factor.** The owner's account is a personal Gmail,
    which has no admin console, no device policy, no administrative revocation and no exportable
    audit. Clara Care's per-role MFA is unchanged: `DOCTOR` and `TENANT_ADMIN` still complete TOTP
    after Google. This reopens only if the account becomes Workspace with 2SV actually enforced.
11. **The patient realm receives nothing.** OTP, `AUTODEC-0037`, the portal cookie and its own host
    are untouched. Google is never offered in the patient realm. Google login is for the team, which
    is what was asked for.
12. **`AUTODEC-0009` decision 10 is superseded.** In a single workspace, copying tokens into three
    apps *is* the duplication that decision told us to wait for, so `projects/tokens` is a library on
    day one. It carries visual tokens and primitives only — no business component, no service, no
    domain type — and a lint rule proves it in CI.
13. **`AUTODEC-0008` decision 1 is amended for the frontend only.** One workspace compiles both
    products' screens, which is a build dependency between *apps*. The **backends** still never call
    each other, share no database, schema, migration or release, and the calendar remains the only
    data channel. Separate apps, separate origins, per-app deploy, and a lint rule forbidding a
    cross-product import.
14. **`/console` keeps serving until the four gates of `AUTODEC-0009` decision 8 are green in CI.**
    The redirect to the new origin is behind a variable, and the variable flips on the tests, not on
    the screen looking finished.

## Rationale
Decisions 1 and 2 are the same judgement twice: the owner asked for an outcome — one Google account
across both products — and named a component. The outcome is available without the component, and the
component would have replaced audited, frozen code with a worse copy written in one week. Saying yes
to the outcome and no to the component is the honest answer, not a refusal of the request.

Decision 3 is what makes decision 6 safe. Sharing an identity is only acceptable while nothing else is
shared, and host-only cookies on three origins make that structural instead of aspirational.

Decision 8 is where this document argues with `AUTODEC-0008` decision 7, which said eighteen tables is
the cheapest this change will ever be. That is true and it is still not worth paying today: with one
operator the column is insurance against a future with no date, while `operator(idp_subject)` plus
`OperatorContext` at the call sites converts the expensive day into eighteen single-table migrations.
The axis is preserved; only the schedule moves.

Decision 10 is the unpopular one. Letting Google satisfy MFA would outsource the second barrier of a
clinical record to an account the organisation does not administer. It costs one step per twelve-hour
session to refuse.

## Reversibility
- HIGH: 7, 12, 14 — a Caddy block, a library boundary, a feature flag.
- MEDIUM: 1, 2, 3, 5, 11, 13 — configuration and habit. Adding an identity service later is a normal
  project; the three named triggers are in decision 1's rationale.
- LOW: 6, 8, 9 — once a Google `sub` is bound to a staff account, unbinding is an identity migration;
  once a wrong tenancy axis reaches eighteen tables, the fix is a data migration and a review.

## Impact
No code in this repository changes as a consequence of this document. `00-Start/POC-Freeze.md` is
untouched, `WP-POC-008` is still `READY` and nothing here alters detection, extraction, the rule
engine, the claim path or the 42-operation contract.

`WP-MVP-006` is added to `10-Roadmap/work-packages.yaml` as `PLANNED`, with `V14__operator.sql` and
the two `ConsoleSessionFilter` changes `AUTODEC-0009` decision 5 already authorised. `MANIFEST.json`
and the validator summary go to 14 work packages.

The Clara Care side is recorded there as `AUTODEC-0050`, amending its own `AUTODEC-0010` with the
Google login leg and one migration adding `app.staff_account.google_sub UNIQUE`. Neither needs a new
`DEC` — see the context above. That migration's **number is deliberately not named**: Clara Care
gains migrations faster than a decision document is read, so the implementing work package takes the
next free one.

## Evidence
- Clara Care baseline read 2026-08-26: no `Dockerfile`, no `docker-compose.prod.yml`, `.github/workflows`
  containing only `ci.yml`, `infra/compose.yaml` for development, 30 migrations, and
  `09-Decisions/DEC-ARCH-008-Session-Security.md` fixing the session mechanism rather than the login
  credential.
- `grep -rn operator_id src/main/kotlin` — no matches, across 18 tables.
- `09-Decisions/AUTODEC-0009-Frontend-Embedding-Boundary.md` — decisions 1, 5, 6, 8, 9, 10 and 12,
  four preserved and three changed here.
- `09-Decisions/AUTODEC-0008-Clara-Care-Integration-Boundary.md` — decision 1 amended for the
  frontend, decision 7 preserved in axis and contradicted in timing.
- `12-MVP/Frontend-Angular.md` — the module this workspace will build.
- `src/main/kotlin/br/com/shiftcatcher/console/ConsoleSessionFilter.kt` — the `SameSite=Strict`
  session cookie and the two changes decision 5 already authorised.

## Status
ACTIVE
