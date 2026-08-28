# AUTODEC-0012 — Clara Care is the identity source

- **Status:** ACCEPTED
- **Date:** 2026-08-27
- **Scope:** authentication in this product; the handoff endpoint in Clara Care; the menu link in the shared frontend workspace
- **Supersedes:** `AUTODEC-0009` decision 6 entirely. Replaces the *source* of identity in `AUTODEC-0010` decision 6, and leaves the rest of that decision standing.
- **Frozen decisions touched:** none. `DEC-ARCH-009` separates the **staff** realm from the **patient** realm and is untouched — this joins staff to staff. `DEC-ARCH-008` fixes the session mechanism, not the credential presented at login, and the mechanism does not change.

## Why this exists

The project owner said it in one sentence: *"O sistema que controla os plantões e tudo mais deve ser
o mesmo login do clara-care, inclusive, utilizar os mesmos cookies, é o mesmo sistema."*

`AUTODEC-0009` decision 6 said the opposite — no shared sign-in, not now and not as a side effect.
`AUTODEC-0010` decision 6 met the owner halfway: the same **Google account** signs in to both, and
nothing else is shared. Neither was built. What exists today is what `AUTODEC-0009` described and
never replaced.

Four facts were read off disk before answering, and two of them changed the question:

1. **This product has no login.** There is no `operator` table — migrations stop at `V9`, and the
   `V14__operator.sql` that `AUTODEC-0010` decision 8 promised was never written. There is not one
   line of OAuth or Google code. `ConsoleSessionFilter` signs in with the **static
   `ADMIN_API_TOKEN`** and hands the browser a session cookie derived from it. There is exactly one
   user: whoever knows the token. So this is not the merging of two sign-ins. It is the first one
   this product has ever had, and Clara Care's is the only serious candidate — opaque sessions in
   Spring Session JDBC, Argon2id, TOTP with a rotatable key ring, mandatory invitation, per-IP and
   per-account rate limiting, revocation handles and an append-only security event ledger, all built
   and frozen.
2. **The owner's sentence is smaller than it sounds.** `DEC-ARCH-009` freezes the wall between the
   clinic's team and the patient. The Plantões operator and the Clara Care team are the same person.
   Nothing here approaches that wall.
3. **`__Host-cc_session` decides part of it by itself.** Clara Care's cookie carries the `__Host-`
   prefix, and that prefix *forbids* a `Domain` attribute — a browser rule, not a setting. While it
   exists the cookie is host-only and cannot reach another host. Literally "the same cookie"
   therefore requires either dropping the prefix or collapsing to one origin.
4. **Shift Catcher renders text strangers wrote.** That is the whole product: WhatsApp group
   messages, parsed and displayed.

## The gap

"The same login, the same cookies" has three technical readings and they differ by orders of
magnitude in risk, not in effort:

- **One origin**, Plantões under `claracare.com.br/plantoes`. One host, one cookie, one app.
- **A parent-domain cookie** for `.claracare.com.br`, which needs both backends validating one
  session, which means a shared session store and the end of independent releases.
- **One identity, two sessions.** The person signs in once and is recognised on both; each product
  keeps its own cookie on its own origin.

The first two put the renderer of stranger-written WhatsApp text on the same origin, or behind the
same cookie, as a multi-tenant clinical record. `AUTODEC-0010` rejected the merged origin on exactly
that argument and it has not weakened: an XSS in the message renderer performs a same-origin
`fetch('/api/v1/patients/…')`, the browser attaches the clinical session cookie, and the response is
readable. Row-level security does not help — the session belongs to a legitimate user of that
tenant. The blast radius stops being this bot's console and becomes every record in the active
tenant, which under LGPD is a clinical data breach rather than a defaced page.

The owner was shown all three with that consequence attached, and chose the third.

## Alternatives

- **Google as the shared leg**, per `AUTODEC-0010` decision 2. Not rejected — deferred, and this
  decision is compatible with it. It needs a Google Cloud project, two OAuth clients and two
  secrets that nobody has provisioned, and it delivers exactly the same user experience as what is
  built here while making Clara Care's own accounts the second-class path. When those credentials
  exist, Google becomes a login leg *inside Clara Care*, and this handoff keeps working unchanged
  because it never cared how Clara Care established who you are.
- **A back-channel ticket** — an opaque one-time string in the link, redeemed by Plantões calling
  Clara Care server to server. Less code and no key distribution, and rejected for one reason:
  `AUTODEC-0008` decision 1 says the backends never call each other, and `AUTODEC-0010` decision 13
  amended that for the frontend *only*, deliberately. A redemption call is exactly the runtime
  dependency both decisions exist to prevent.
- **A shared session store.** Rejected: it couples the two databases and the two release cycles,
  and it hands a compromise of either product a session that opens the other.

## Decisions

### 1. Clara Care is the identity source; this product remains its own authority

Clara Care answers **who you are**. This product answers **what you may do** — from its own table,
in its own database. Authorisation never crosses the boundary: no role, no permission and no
`tenant_id` travels in the handoff, and none would be honoured if it did.

### 2. The handoff is a signed assertion, verified offline

Clara Care mints a compact assertion when an authenticated staff member follows the Plantões link.
It is signed with Ed25519 and this product verifies it with a **public key held in its own
configuration**. No call is made in either direction, so `AUTODEC-0008` decision 1 stands as
written: the backends still never talk to each other.

**It is not a JWT, and that is the security decision rather than a preference.** A JWT announces its
own algorithm in a header the attacker controls, and the whole confusion family — `alg: none`, an
RS256 token verified as HS256 with the public key as the secret — exists only because there is a
negotiation to attack. The bytes here are `base64url(payload) "." base64url(signature)`, the
algorithm is always Ed25519, and neither half writes it down, so neither can be told to change it.
A token arriving with two dots is refused for having the *shape* of a JWT: a verifier that reads a
header at all is one that can be talked into reading the wrong one.

The primitive is nobody's invention — Ed25519 has been in the JDK since 15, and both halves call it.
That is what lets a service with neither Spring Security nor a JOSE library gain no dependency at
all, and it is why "roll your own" does not apply: what is hand-written is a container with one
algorithm and no negotiation, which is strictly less surface than the standard it replaces.

The assertion is deliberately the narrowest thing that can carry an introduction:

| claim | value | why |
|---|---|---|
| `iss` | Clara Care's origin | one issuer, checked |
| `aud` | `plantoes` | an assertion for this product cannot be replayed at another |
| `sub` | the staff **user id** | not the email — an email is reassignable by an administrator and is the classic account-takeover seam |
| `name` | display name | so the operator has a name on screen without a second lookup |
| `jti` | random | single use, see decision 4 |
| `exp` | 60 seconds | it is a doorway, not a credential |

Signing this is not the OIDC provider that `AUTODEC-0010` refused to hand-write. That refusal was
about ~1.5k lines of discovery, authorisation endpoint, consent, refresh and token introspection.
This is one audience, one algorithm, one minute, no refresh and no consent — under a hundred lines
on each side, and the half that matters is the JDK's. The line is worth naming precisely because it
is a line that moves.

### 3. Sessions stay separate, and so do cookies and origins

`AUTODEC-0010` decision 3 stands unchanged. Three origins, every cookie host-only, no cookie ever
issued for a parent domain. This product creates **its own** session on redemption, with its own
`SameSite=Strict`, `HttpOnly`, `Secure` cookie, exactly as `AUTODEC-0009` decision 5 requires. What
the owner asked for — one login — is delivered by the person never seeing a second sign-in screen,
not by two products sharing a credential.

The consequence is worth stating rather than discovering: **revocation is per product.** Ending a
Clara Care session does not end the Plantões one, which lives until its own timeout. The assertion
is an introduction, not a standing authorisation. If a revocation that spans both is ever needed,
it is its own decision and it starts by giving this product a reason to ask.

### 4. One assertion, one session

A `jti` is recorded on redemption and refused on a second presentation, and an assertion older than
sixty seconds is refused whether or not it was seen before. A link that grants a session is a
credential in a URL — in browser history, in a referrer, over somebody's shoulder — and the only
honest answer is that it stops working almost immediately and cannot be used twice.

### 5. An unknown subject is 403, and self-provisioning does not exist

`operator(id, idp_subject UNIQUE, display_name, created_at)` arrives in `V10__operator.sql` with one
seeded row, as `AUTODEC-0010` decision 8 specified and at the number the migrations actually reached.
`idp_subject` holds Clara Care's user id. A valid assertion for a subject with no row is **403, not
a new operator**: the fact that Clara Care knows somebody says nothing about whether this product
should. Linking a new operator is an act somebody performs, not a side effect of a first visit.

### 6. `ADMIN_API_TOKEN` stops being a person's credential

`ConsoleSessionFilter`'s token login is removed once the handoff works. The token's other job —
machine access — is a separate question that this decision does not settle, and `AUTODEC-0010`
decision 7 already names where it goes.

### 7. `AUTODEC-0009` decision 1 still stands

The menu item is still a **link**, to this module on its own origin. It now carries a handoff in its
query string. A link that signs you in is still a link, and nothing about the browser boundary
changed.

### 8. The door on Clara Care's side is not under `/api/v1`

It is `GET /sso/plantoes`: a browser navigation with no request body, no response body and nothing
of the domain in it, which answers `302` with the assertion in the `Location`. Putting it in the
versioned contract would add an operation to the catalogue, to the generated TypeScript client, to
the endpoint coverage files and to the contract tests, for something no API client will ever call.
`AUTODEC-0009` decision 3 set that precedent from the other direction and gave the reason: a second
entrance onto the same product is not new product surface. `openapi/openapi.yaml` is untouched, and
its operation count does not move.

Clara Care's security chain ends in `anyRequest().denyAll()`, so the path is unreachable until it is
named — it is named with `ROLE_STAFF_IDENTITY` **and** `MFA_VERIFIED`. A session that has not
finished proving who it is must not be able to extend itself into a second product.

## Configuring it

The two halves share one Ed25519 key pair. It is generated once, by hand, and never by this
codebase:

```bash
openssl genpkey -algorithm ed25519 -out plantoes-handoff.pem
openssl pkey -in plantoes-handoff.pem -outform DER | base64 -w0
openssl pkey -in plantoes-handoff.pem -pubout -outform DER | base64 -w0
```

The second line is the **private** key, PKCS#8, and it belongs in Clara Care's environment as
`CLARACARE_HANDOFF_PLANTOES_PRIVATEKEY`. The third is the **public** key, X.509, and it belongs in
this product's as `SHIFT_CATCHER_HANDOFF_PUBLICKEY`. The `.pem` itself belongs nowhere afterwards.

Clara Care also needs `CLARACARE_HANDOFF_PLANTOES_ENTRYURL` pointing at
`https://pego.claracare.com.br/console/entrada`, and the frontend's `plantoesUrl` becomes the
relative `/sso/plantoes` rather than the other product's origin — the menu item now goes through
this product's own door, which is what mints the introduction.

Both sides default to blank and both refuse everything while blank. An installation that has not
been given a key has not been told who is allowed to vouch for anybody, which is the right thing for
a deployment to believe until somebody tells it otherwise.

## What this costs

- A key pair becomes a deploy artifact: a private key in Clara Care's environment and a public key
  in this product's. Two secrets that did not exist, and a rotation nobody has had to think about.
- Clara Care gains one endpoint that issues something credential-shaped, in a codebase whose whole
  identity surface was until now inbound.
- The frontend link stops being a constant and becomes a call, which means it can fail, which means
  the menu item needs a failure it can show.

## Open

- Google as a leg inside Clara Care, per `AUTODEC-0010` decision 2, whenever those credentials exist.
- Threading `OperatorContext` through the services that will one day filter by operator, per
  `AUTODEC-0010` decision 8. The table arrives here; the eighteen columns still wait for the trigger
  named in `AUTODEC-0010` decision 12.
