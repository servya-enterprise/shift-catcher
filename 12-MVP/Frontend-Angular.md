# Frontend — Angular module

Status: **PROPOSED — not built.** No Angular code exists in this repository and none is authorised by
this document. Like `12-MVP/MVP-Scope.md` and `12-MVP/Calendar-Integration.md`, this sits outside the
frozen POC scope: `00-Start/POC-Freeze.md` lists a frontend among the explicit non-goals and
`WP-POC-008` has not run. Nothing here moves a benchmark verdict, because nothing here touches
detection, extraction, the rule engine or the claim path.

The work is `WP-MVP-005`. The binding boundary decisions are in
`09-Decisions/AUTODEC-0009-Frontend-Embedding-Boundary.md`.

## What this replaces, and what it does not

`WP-MVP-002` shipped `/console`: seven Thymeleaf pages, server-rendered, calling the same services
in-process. It works, and it is the only screen the operator has. This document specifies its
successor as an Angular application, and the successor inherits three properties that were not
accidents and must survive the port:

1. **The admin token never reaches the browser.** Sign-in exchanges `ADMIN_API_TOKEN` for a session;
   the browser carries a session id that is useless anywhere else and expires. A page that renders
   text strangers wrote must not also hold a credential.
2. **Escaping is the default, not a discipline.** There is no `th:utext` anywhere in the current
   templates. Angular interpolation escapes by the same logic, and `[innerHTML]` is banned in this
   application by lint rule for the same reason.
3. **A reload never re-claims a shift.** Today that comes free from POST/redirect/GET. A SPA has no
   such mechanism, so it becomes the application's job — see "The four risks the server did not
   have".

What it does not replace: `/api/v1` and its 42 operations, the admin token as the API's credential,
or any service, rule or migration. This is a front door, not product.

## The embedding question

"An option in the Clara Care menu" has three technical readings, and they are not equivalent.

| Reading | What it costs | Verdict |
|---|---|---|
| **A menu link.** Clara Care's menu carries an item that navigates to this module on its own origin. | Nothing. Same-origin session, `SameSite=strict` unchanged, no shared config. | **Chosen.** |
| **An iframe.** Clara Care frames the module. | The session cookie becomes third-party: `SameSite=strict` stops being sent, so the cookie must go `SameSite=None; Secure; Partitioned`, `X-Frame-Options` must come off and `frame-ancestors` must name Clara Care's origin in this app's config. | Reversible upgrade, deliberately later. |
| **Module federation / Angular Elements** loaded into Clara Care's build. | A build dependency between the repositories. | **Rejected** — `02-Architecture/Clara-Care-Reuse-Strategy.md` and `AUTODEC-0008` decision 1. |

The link satisfies what was actually asked — the module is reached from the Clara Care menu — at zero
architectural cost, and it is what the design already assumes: no brand mark, no application header,
no footer, no global sign-out, because the host owns those. The module draws only its own five-tab
internal navigation.

It is also, today, the only reading that is available at all. The menu is not yet built: Clara Care's
`backoffice` and `portal` are both untouched Angular CLI scaffolds with empty route arrays, so there
is no shell to be framed by and no navigation to be added to. That does not weaken the decision, it
dates it — the link is what the module offers, and the menu item is one line in an application that
will exist later. What the two products share in the meantime is the design system, not the shell.

## The seam: a JSON front door, not new API

The Angular application cannot call services in-process. It needs HTTP, and there are two ways to
give it some:

- **Widen `/api/v1` to accept the console session.** This puts session auth and CSRF on the
  contract's hot surface, and it makes the browser assemble screens out of resource calls — the
  opportunity list plus every message, to render one card's quoted text.
- **A screen-shaped JSON front door under `/console/api/*`**, reusing the console session, calling
  the same services in-process, and returning exactly what one screen needs. **Chosen.**

This is the precedent `WP-MVP-002` already set and this document makes explicit: `/console` today
serves roughly fourteen HTTP operations that are deliberately absent from
`06-API/Endpoint-Catalog.md`, because they are a second entrance onto the same product rather than
new product. `/console/api/*` is the same thing one level more legible. **The `/api/v1` contract
stays at 42 operations and `openapi/poc-openapi.yaml` is untouched.**

Front-door operations, listed here because a thing that exists should be written down somewhere:

| Method | Path | Serves | In-process source |
|---|---|---|---|
| POST | `/console/api/session` | sign-in | `ShiftCatcherProperties.security.adminApiToken` |
| GET | `/console/api/session` | recover after a reload | the session itself |
| DELETE | `/console/api/session` | sign-out | — |
| GET | `/console/api/board` | Ofertas | `ShiftOpportunityService.list` + `IngestionService.list` + `GroupService` |
| GET | `/console/api/opportunities/{id}` | Detalhe | `ShiftOpportunityService.detail` + `IngestionService` |
| POST | `/console/api/opportunities/{id}/claim` | Pegar | `ClaimService.claim` |
| POST | `/console/api/opportunities/{id}/review` | Salvar leitura | `ShiftOpportunityService.review` |
| POST | `/console/api/opportunities/{id}/reevaluate` | Reavaliar | `OpportunityEvaluationService.reevaluate` |
| POST | `/console/api/opportunities/{id}/ignore` | Descartar | `ShiftOpportunityService.ignore` |
| GET | `/console/api/claims` | Respostas | `ClaimService.list` |
| POST | `/console/api/claims/{id}/retry` | Tentar de novo | `ClaimService.retry` |
| POST | `/console/api/claims/{id}/retract` | Apagar a mensagem | `ClaimService.retract` |
| GET/POST | `/console/api/agenda` | Agenda | `AvailabilityService.list` / `.create` |
| DELETE | `/console/api/agenda/{id}` | Remover | `AvailabilityService.delete` |
| GET | `/console/api/messages` | Mensagens | `IngestionService.list` |
| GET/PUT | `/console/api/settings/claim-message` | Ajustes | `ClaimMessageService` |

`GET /console/api/session` was missing from the first version of this table, and its absence was the
most consequential thing in it. The console cookie is `HttpOnly`, so the app cannot read the CSRF
token from it, and the token was only ever issued at sign-in. After a page reload the operator is
still authenticated — the cookie is good for eight hours — and the app has no token, so every
state-changing request answers 403 and her only remedy is to sign out and back in. See
[[09-Decisions/AUTODEC-0011-Console-JSON-Front-Door]].

**Four** changes to `ConsoleSessionFilter` are required, not the two first written here, and they are
the whole of the backend security work:

1. accept the CSRF token from an `X-CSRF-Token` **header** as well as a request parameter (a JSON
   body field is not a request parameter);
2. answer an unauthenticated request under `/console/api/*` with **401 `application/problem+json`
   written by the filter** rather than a redirect — a browser follows a redirect transparently, so
   the app would receive 200 with a sign-in page in the body, and `sendError` is no better because
   `server.error.include-message: never` strips the message and may return HTML;
3. exempt `POST /console/api/session` from the authentication check — today the only exemption is an
   exact-URI match on `/console/login`, so the sign-in fetch is redirected before it reaches a
   handler. The exemption matches URI **and** method, so `GET` and `DELETE` on the same path still
   require a session;
4. check CSRF on **every unsafe method**, not only `POST`. The server-rendered console only ever
   posts; the JSON door uses `PUT` and `DELETE`, and a check that names one verb protects one verb.

## Every screen already has its data

This was the question worth answering before writing a line of Angular, and the answer is good: the
design needs **no new domain data and no new persisted field**. Everything either exists on a
response already or is derivable from one.

| Screen element | Where it comes from | Note |
|---|---|---|
| Window `19:00 → 07:00 (+1)` | `ConsoleFormatter.window` | Reuse it. Its `"data?"` / `"horário?"` fallbacks are already the designed empty states. |
| Money `R$ 1.800` | `ConsoleFormatter.money` | Reuse it. |
| Duration `12h` | derived from start/end/`endsNextDay` | New computation, no new field. |
| Status rail and section | `OpportunityStatus` → `tone()` | Already exists in `ConsoleViewModels`. |
| Quoted text, sender | `IngestionService.list()` joined on `sourceMessageId` | The console already does this join; the front door keeps it server-side so the browser never downloads every third-party message. |
| `Lida pelo modelo · 62%` | `extractionMethod == AI_FALLBACK`, `confidence` | Exists. |
| `Não deu para ler` | `ambiguousFields` | Exists. |
| Closed-row reason | `resolutionReason` | **Verify the mapping.** These are codes such as `ESSENTIAL_FIELD_AMBIGUOUS`; each needs a pt-BR sentence. Anything unmapped renders the neutral `encerrada`, never a raw code. |
| `virou uma oferta` on Mensagens | join `sourceMessageId` across the opportunity list | Derivable. |
| Pulse `3 grupos ouvindo · 14:32` | enabled group count + newest `receivedAt` | Derivable. |
| `0,9 s` on Respostas | `claimedAt − decidedAt` | Derivable. This is the product's own metric and it belongs on screen. |
| Connection-down banner | `GreenApi` instance state (`EP-005`'s service) | Exists. |

One designed control did **not** survive contact with the domain, and the design was corrected
rather than the domain: the review form had a checkbox "atravessa para o dia seguinte" and a
cross-field error when the end time preceded the start. Both were wrong.
`ShiftOpportunityService.review` **derives** `endsNextDay` as `!endTime.isAfter(startTime)`, so
19:00 → 07:00 is already understood as crossing midnight and `ReviewOpportunityRequest` has no field
to carry a checkbox anyway. The form now shows the derivation as feedback — *"Termina 07:00 do dia
27 — 12 horas"* — instead of asking a question the system has already answered.

The agenda form is the opposite case: `CreateAvailabilityRequest` **takes** `endsNextDay` explicitly,
the controller already accepts the parameter, and `agenda.html` never rendered the checkbox. There
the control is real and missing, and the Angular form adds it.

## The shared design system

The tokens and components below are **not Shift Catcher's alone**. Clara Care's
`02-Architecture/Frontend-Architecture.md` already specifies "libs internas para design system"
inside its Angular workspace, and as of 2026-08-25 that lib does not exist: `projects/backoffice` and
`projects/portal` are both untouched `ng new` output, nine files each, with empty route arrays. There
is no host application to plug into and no visual language to match — which makes this the cheapest
moment there will ever be to decide that both products look like one company.

So this is the first real exercise of a Servya design system, and Shift Catcher is where it gets
proven against real screens rather than against a swatch page.

**Copied now, extracted later.** The tokens and the nine components live in this project and are
copied into Clara Care's workspace when its apps get built. Not a published package, not a Gradle
dependency, not a submodule — a build dependency between the two repositories is exactly what
`02-Architecture/Clara-Care-Reuse-Strategy.md` exists to prevent and `AUTODEC-0008` decision 1
forbids. This is the same reasoning `12-MVP/Clara-Care-Integration.md` applied to the calendar
package: a copy duplicates and drifts, but drifts slowly for something whose contract is a colour
value, and costs nothing until a second consumer exists. When the duplication starts to hurt — which
means when Clara Care's frontend is real — the shared piece is extracted into a lib that neither
product owns.

**The application is not shared.** Same look, separate apps, separate origins, separate sessions. The
gate is authentication, not code: this project has a static admin token and one implicit operator,
Clara Care has `tenant_id` with row level security, and `AUTODEC-0008` decision 7 already recorded
that the two do not divide the world along the same line. Merging the UI would merge the sign-in by
construction and decide the identity axis by accident, which is the specific mistake that document
exists to prevent.

Where the two already agree, they agree without having been made to, and that is the evidence they
can share a visual layer safely: Problem Details for errors, session material in `HttpOnly` cookies,
nothing sensitive in `localStorage`, typed reactive forms, signals for local state, WCAG AA as a
practical gate. Where they differ is the seam that keeps them apart: Clara Care generates its API
client from a frozen OpenAPI document and forbids hand-written DTOs, while this module's front door
is deliberately outside `/api/v1` and outside any spec. One convention cannot serve both, and it does
not have to.

## Design tokens

Copy verbatim into `styles/tokens.css`. Names are the contract; no component may use a literal
colour. The light block defines the complete palette; dark redefines the same names, so no component
knows two palettes — it knows one, with two values.

Every value is **measured, not estimated**: converted from oklch to sRGB and run through a WCAG
contrast calculation, both themes, by `scripts/verify_design_tokens.mjs`. The hex in each comment is
what a browser actually paints, so the numbers can be checked without trusting this document.

```css
:root {
  --sc-ground:      oklch(97.2% 0.004 245);  /* #F4F6F8 */
  --sc-surface:     oklch(100%  0     0);    /* #FFFFFF */
  --sc-line:        oklch(90%   0.006 245);  /* #DBDEE2  separation only */
  --sc-line-soft:   oklch(94%   0.005 245);  /* #E8EBEE */
  --sc-line-strong: oklch(63.6% 0.008 245);  /* #878C90  control boundaries */
  --sc-ink:         oklch(24%   0.015 250);  /* #1A2026 */
  --sc-ink-2:       oklch(41%   0.013 250);  /* #454B51 */
  --sc-muted:       oklch(54.4% 0.011 250);  /* #6B7076 */
  --sc-go:          oklch(50%   0.119 155);  /* #0E7644 */
  --sc-go-ink:      oklch(99%   0     0);    /* #FCFCFC */
  --sc-go-tint:     oklch(95%   0.035 155);  /* #DDF6E4 */
  --sc-wait:        oklch(52%   0.111 66);   /* #935A0D */
  --sc-wait-tint:   oklch(95%   0.042 78);   /* #FEECD0 */
  --sc-stop:        oklch(52%   0.16  27);   /* #B33832 */
  --sc-stop-tint:   oklch(95%   0.024 27);   /* #FEE9E6 */
  --sc-idle:        oklch(65.8% 0.008 250);  /* #8E9296 */
}

@media (prefers-color-scheme: dark) {
  :root:not([data-sc-theme="light"]) { /* dark values below */ }
}
:root[data-sc-theme="dark"] { /* the same dark values */ }
```

Dark values: `--sc-ground: oklch(17.5% 0.01 250)` `#0D1115`, `--sc-surface: oklch(21.5% 0.012 250)`
`#151A1F`, `--sc-line: oklch(30% 0.014 250)` `#292E35`, `--sc-line-soft: oklch(25% 0.012 250)`
`#1D2227`, `--sc-line-strong: oklch(51.6% 0.014 250)` `#626970`, `--sc-ink: oklch(95% 0.005 250)`
`#ECEFF2`, `--sc-ink-2: oklch(80% 0.008 250)` `#BABEC3`, `--sc-muted: oklch(66% 0.013 250)`
`#8C939A`, `--sc-go: oklch(74% 0.15 155)` `#4BC680`, `--sc-go-ink: oklch(16% 0.03 155)` `#031108`,
`--sc-go-tint: oklch(27% 0.055 155)` `#0B2E1A`, `--sc-wait: oklch(80% 0.13 78)` `#EBB353`,
`--sc-wait-tint: oklch(28% 0.055 78)` `#382503`, `--sc-stop: oklch(72% 0.15 27)` `#F47C70`,
`--sc-stop-tint: oklch(27% 0.06 27)` `#3F1916`, `--sc-idle: oklch(51.4% 0.01 250)` `#63686D`.

**Two line tokens, because a border does two different jobs.** `--sc-line` separates a card from the
page; it is decorative and may be as quiet as it likes. `--sc-line-strong` is the *boundary of a
control* — the edge of an input, of an outlined button, of an unfilled badge. WCAG 1.4.11 asks 3:1
for that, and the single soft line the first draft used measures **1.35:1**: a field whose only edge
is invisible. Never put `--sc-line` on something a person is meant to click or type into.

Nothing here sits outside sRGB. An out-of-gamut oklch value is silently clipped by the browser, so
the colour that ships stops being the colour that was specified and its measured contrast stops being
true. Four tokens in the first draft were clipped that way, `--sc-stop-tint` worst of all — its
chroma had to come down from `0.04` to `0.024` to be real.

Three tokens carry the whole semantic load, and each has exactly one meaning across all six screens:

- `--sc-go` — **she can act now.** The only saturated fill in the module: the *Pegar* button. Also the
  ready rail, the ready section bar, the "no grupo" badge and the live dot.
- `--sc-wait` — **the system needs her.** Outline only, never a fill: *Conferir*, the review rail,
  the confidence note.
- `--sc-stop` — **an effect did not happen.** Reserved for a claim that never reached the group, a
  dead provider connection, and field-level validation. Never for "rejected by rules" — a rule
  refusal is a correct outcome, not a failure, and takes `--sc-idle`.

The theme reads `prefers-color-scheme` and an explicit `data-sc-theme` attribute wins over it, so
the host page can pin the module to its own theme later without a code change.

### Typography

`IBM Plex Sans` and `IBM Plex Mono`, self-hosted as woff2 under `assets/fonts` with
`font-display: swap`. Not a CDN: the module must render inside a clinic's network on a bad
connection, and a blocked font host would leave her reading a fallback at the moment she is deciding
in seconds. Fallback stacks: `'IBM Plex Sans', system-ui, sans-serif` and
`'IBM Plex Mono', ui-monospace, monospace`.

Mono carries everything that is a number or an identifier; Sans carries everything that is a
sentence. That split is functional before it is aesthetic: tabular digits align invisible columns
down a list scrolled with a thumb, so two windows can be compared without being read.

| Role | Face | Size / weight | Extra |
|---|---|---|---|
| `window-lg` | Mono | 25 / 500 | `tabular-nums`, `letter-spacing: -.01em` |
| `window-md` | Mono | 20–22 / 500 | Respostas, Agenda, desktop list |
| `window-xl` | Mono | 38 / 500 | desktop detail only |
| `money` | Mono | 16 / 600 | `tabular-nums` |
| `eyebrow` | Mono | 11 / 600 | `.1em`, uppercase — the date line |
| `section` | Sans | 11.5 / 700 | `.09em`, uppercase |
| `body` | Sans | 13.5 / 400 | place, facts |
| `trace` | Sans | 11.5 / 400 | provenance line |
| `action` | Sans | 14.5 / 600 | buttons |
| `badge` | Sans | 10.5 / 700 | `.07em`, uppercase |

### Geometry

Radii: card `10px`, button `8px`, input `7px`, badge `5px`, pill `999px`. The status rail is a `3px`
left border on the card, never a separate element. Card padding `14px 15px 13px`; board padding
`20px 16px 28px`; gap between cards in a section `10px`; between sections `26px`.

Touch targets: primary and secondary buttons `min-height: 46px`, inputs and tertiary actions `44px`.
Below that nothing is tappable. She uses this on a phone, often one-handed, often tired.

## Components

| Component | Variants | Inputs | Notes |
|---|---|---|---|
| `sc-module-nav` | — | `active`, `counts` | The five internal tabs. The module's only chrome. Underline for the active tab; the offer count in mono `--sc-go` beside "Ofertas". |
| `sc-pulse` | `live`, `degraded`, `down` | `groupCount`, `lastMessageAt` | Proves the pipeline is alive. `down` swaps the dot to `--sc-stop` and the sentence to the reason. |
| `sc-section` | `go`, `wait`, `idle` | `title`, `count` | Bar + uppercase title + mono count. The triage device: the eye picks a section before a card. |
| `sc-offer-card` | `go`, `wait`, `idle` | `offer`, `busy` | Rail colour by tone. `go` gets *Pegar*; `wait` gets *Conferir*; `idle` renders as a compact row, not a card. |
| `sc-closed-row` | — | `window`, `reason` | One line. Everything encerrada collapses to this. |
| `sc-badge` | `go`, `wait`, `stop`, `idle` | `label` | Only where sections do not group — Respostas and the detail. In a grouped list it is redundant and omitted. |
| `sc-button` | `go`, `check`, `quiet`, `danger`, `bare` | `busy`, `disabled` | `busy` spins, switches the label to the gerund (*Pegar* → *Pegando*) and blocks re-entry from the first tap. `disabled` is a rule saying no, and no tap will change it — a different thing, drawn differently. `min-height: 46px`, `44px` for `bare`. |
| `sc-banner` | `wait`, `stop` | `title`, `detail`, `actions` | Sits above the content it concerns and never replaces it: she keeps seeing the list, she just cannot act on it. `wait` for an outcome that is the product working — someone claimed first, the offer moved under her. `stop` only for an effect that did not happen. |
| `sc-field` | `text`, `time`, `date`, `money` | `control`, `label`, `hint`, `derived` | Error under the field it belongs to. `derived` is the green counterpart: what the system concluded from what she typed. |
| `sc-empty` | `quiet`, `problem` | `title`, `detail`, `action` | Never a bare "nada aqui". |
| `sc-skeleton` | `card`, `row` | `count` | The shape and height of the real card, so nothing jumps on arrival. |

## Screens

Route order is nav order: `/` (Ofertas), `/ofertas/:id`, `/respostas`, `/agenda`, `/mensagens`,
`/ajustes`. `/console/*` keeps serving the Thymeleaf pages until the Angular routes pass their gates,
then becomes a redirect.

### Ofertas — the home of the module

The list is **triaged before it is read**: `Pode pegar` (go) · `Precisa de você` (wait) ·
`Encerradas hoje` (idle, compact rows). Sorting inside a section is newest first. An empty section
is not rendered; when all three are empty the screen is the `quiet` empty state, which states that
the groups are still being heard — because silence and failure must not look alike.

The card carries five things and nothing else: the date eyebrow, the window with the money right-
aligned on the same baseline, one line of place and duration, one line of provenance, and the
actions. **The quoted WhatsApp text is not on this screen.** It was the noisiest element and the
least used in the decision; it lives on the detail.

`Pegar` is the only saturated fill. A review card gets `Conferir` in `--sc-wait` outline and never
the green button, because claiming is not yet a real option and a button that promises what it
cannot deliver costs a misreading.

### Detalhe da oferta

The window at `window-xl` on desktop, the facts as a bordered grid, the original message quoted in
full with sender, group and time, and the correction form below.

The form's rule is one sentence at the top: *"Preencha só o que estiver errado ou faltando. Campo em
branco mantém o que já foi lido."* Implementing that is the single highest-risk detail in this
document: **a blank control must send `null`, never `""`.** An empty string reaching
`ReviewOpportunityRequest` erases a field the parser had read correctly. The console does this today
with a `String?.orNull()` that trims and nulls the empty; the Angular form does the same on the way
out and it is asserted by a test, not by care.

### Respostas

Chronological, badged, one card each. The message that went to the group is quoted; the timing line
carries the elapsed `decidedAt → claimedAt` in `--sc-go`, because reaction time is the product and
the operator should see it succeeding.

Retract is a disclosure, closed by default, and the warning is not softened: apagar leaves the
"mensagem apagada" mark in the group, and whoever already read it may have assigned her the shift.
Deleting does not undo an agreement.

### Agenda

Grouped `Esta semana` / `Depois`. A commitment carries `pego aqui` (go rail, no delete — it leaves
on retraction) or `você cadastrou` (idle rail, deletable). The registration form takes date
(required), start, end, `endsNextDay` and a label, and states the consequence of leaving the times
blank: the day counts as busy, and an offer that day goes to review instead of being refused on its
own.

### Mensagens

Read-only, day-grouped, time on the left in mono. A message that became an offer carries a `--sc-go`
tail linking to it; a message that did not carries a neutral one with the reason. This screen is
where the `WP-POC-008` corpus comes from, so it renders the text faithfully and never truncates
silently.

### Ajustes

One field, `maxlength=512`, with a live counter and the note that changing it does not rewrite what
was already sent. Below it, a preview of how the reply lands in the group as a quoted WhatsApp
message — the same setting, shown as its effect.

## Validation and error copy

The current error surface leaks two things at the operator, and the port fixes both rather than
carrying them forward:

- **JDK parse messages.** The console does `amount.replace(',', '.').toBigDecimal()`; a malformed
  amount throws `NumberFormatException`, which `act()` catches as `IllegalArgumentException` and
  prints raw. The Angular form validates the amount client-side and the front door returns a Problem
  Details document with a written sentence: *"Só números no valor: 1800 ou 1800,00."*
- **English service messages.** `require(current.status.isOpenForAnalysis())` renders *"Only an
  opportunity that is still open for analysis can be reviewed"* to a pt-BR user. The front door maps
  every `ApiProblemException` code to a pt-BR sentence, and an unmapped code falls back to a written
  sentence naming what failed — never a code, never "algo deu errado".

| Case | Where | Copy |
|---|---|---|
| Amount not a number | field | Só números no valor: 1800 ou 1800,00. |
| Date required (agenda) | field | Escolha a data do plantão. |
| End before start | **none** | Not an error. Render the derivation: *Termina 07:00 do dia 27 — 12 horas.* |
| Reply text empty | field | A resposta não pode ficar em branco. |
| Reply over 512 | counter turns `--sc-stop` | 512 / 512 — máximo. |
| `409` stale version | banner on the card | Esta oferta mudou enquanto você olhava. Recarregamos — confira antes de salvar. Then refetch and repaint. |
| Offer no longer claimable | banner | Alguém pegou este plantão antes. |
| Provider down | screen banner | A conexão com o WhatsApp caiu às 11:04. Nada é enviado enquanto isso. |
| Claim failed | card | O "PEGO" não chegou ao grupo. Tentamos 3 vezes, a última às 11:06. |

Every error names what happened, what it means for her, and what to do. No apologies, no vagueness,
no "algo deu errado".

## The four risks the server did not have

Server rendering gave `WP-MVP-002` four properties for free. A SPA gives none of them, and each one
is a way to send a real WhatsApp message by accident.

1. **Double submit.** POST/redirect/GET made a reload harmless. Now `Pegar` must disable on the first
   click, stay disabled while the request is in flight, and generate a claim key that survives a
   component remount. The claim path is already idempotent per opportunity server-side; this is
   defence in front of it, not instead of it.
2. **Stale optimistic version.** `version` round-trips on review, ignore and the claim message. A
   long-lived SPA holds one much longer than a page that reloaded. A `409` is routine, not
   exceptional: show the banner, refetch, repaint, never retry silently.
3. **A credential in a page that renders strangers' text.** The session cookie must stay
   `HttpOnly`; no token, ever, in `localStorage`. `[innerHTML]` is banned by lint rule across the
   application.
4. **Polling on a latency product.** The list must refresh itself — she should not have to pull. Poll
   `/console/api/board` every 15 s while the tab is visible, pause on `visibilitychange`, and back off
   to 60 s after two consecutive failures. It reads the database, never GREEN-API, so it cannot slow
   the send path — but it shares the connection pool, which is why the interval is a configuration
   value and not a constant. This is visibility, not notification: nobody is *told* when the pipeline
   stops. The honest ceiling until `NotificationPort` has an implementation.

## Responsive

| Width | Layout |
|---|---|
| `< 768px` | Single column, 16px gutters. The design target — she uses this on a phone. |
| `768–1023px` | Single column capped at 560px, centred. Cards do not stretch; a 900px-wide offer card is harder to scan, not easier. |
| `≥ 1024px` | Two panes: a 400px list rail and a detail pane. Selecting a card fills the pane rather than navigating. `/ofertas/:id` still resolves standalone, for a link opened cold. The internal nav is the only component that changes shape rather than just reflowing: `gap` 14→20px, gutters 16→24px, label 13→13.5px. Everything else — card, button, field, badge, rail — is one definition at every width. |

The module never sets a page background outside its own root, never uses `position: fixed`, and
never assumes it owns the viewport — it may be a pane inside something else later.

## Accessibility

- Contrast: **44 pairs measured across both themes, zero failures.**
  `scripts/verify_design_tokens.mjs` is the gate and exits non-zero, so this is a build step rather
  than a promise — the first draft of these tokens failed seven of those pairs while the document
  claimed they all passed. Body text clears 4.5:1; control boundaries and status rails clear 3:1.
  The rails also carry a redundant label, never colour alone: a `3px` green border is a hint, the
  section title is the fact.
- Focus: visible ring on every interactive element, `2px` `--sc-go` offset `2px`. Never
  `outline: none`.
- Order: nav → pulse → sections in visual order → cards in visual order → actions within a card.
- Routing: an `aria-live="polite"` region announces the screen name on navigation, since a SPA does
  not reload and a screen reader is otherwise not told.
- Actions: `Pegar` announces `Pegar plantão de 25 de agosto, 19:00 às 07:00` — the visible label alone
  is ambiguous in a list of five.
- Motion: the only animation is the skeleton shimmer, `1.6s ease-in-out`, and it is removed under
  `prefers-reduced-motion: reduce`. Nothing else moves; a list that animates while she decides is
  working against the product.

## Angular

Angular 22.1, standalone components, strict TypeScript, signals for state,
`ChangeDetectionStrategy.OnPush` everywhere, typed reactive forms,
`provideHttpClient(withFetch())`. Node 24.19.0, pnpm 11.19.0, Vitest.

Those versions are not a preference. They are Clara Care's, pinned by its `DEC-ARCH-015` — ACCEPTED
and FROZEN, and explicitly not revocable by an AUTODEC. Matching them is the whole cost of keeping a
component set shareable: a component written against Angular 22 signals does not move to a workspace
on 20 without being rewritten, and the version is the one thing about a shared design system that
cannot be papered over later.

No third-party component library. The design is nine components and a token file, and a library would
cost more in overriding than it saves.

```
src/app/
  core/          session, http interceptor (CSRF header, 401 → sign-in, problem-details mapping)
  shared/        sc-button, sc-badge, sc-field, sc-empty, sc-skeleton, formatting pipes
  features/
    ofertas/     board (list + sections), detalhe (facts + correction form)
    respostas/   list + retract disclosure
    agenda/      list + registration form
    mensagens/   read-only log
    ajustes/     claim message + preview
  styles/        tokens.css, base.css, fonts
```

The build outputs static assets served by the same Spring application, on the same origin. No CORS,
no second container, no second deployment — the one-container operational model of
`12-MVP/MVP-Scope.md` is preserved.

Formatting stays on the server. `ConsoleFormatter` already produces the exact window and money
strings this design shows, including the `"data?"` and `"horário?"` fallbacks, and it is already
covered by tests. The front door sends both the formatted string and the raw fields — the string for
display, the raw values for the correction form.

## Gates

Beyond a clean build and ktlint:

- A hostile group message renders as text, never as markup. The direct equivalent of
  `ConsoleControllerTest`'s existing assertion, and the one test that must exist before anything
  ships.
- A blank field in the correction form leaves the previously-read value intact, asserted end to end.
- A double click on `Pegar` produces one claim.
- A `409` repaints from the server instead of retrying.
- An unauthenticated request to `/console/api/*` gets `401`, not a redirect.
- Every route renders under `prefers-reduced-motion: reduce` and in both themes.
- Contrast is measured, not assumed.

Tests run in CI. On the authoring machine the working copy sits under a non-ASCII path that Gradle's
test executor cannot load classes from, and the Docker daemon is frequently down — CI is the gate,
and saying otherwise would be saying tests passed when they were not run.

## What this must not move

`WP-POC-008` has not run. This work adds no rule, changes no parser, and touches no message on the
way in. The claim path it calls is the same one `EP-023` calls. If a benchmark verdict moves because
of this work package, something is wrong with the work package.
