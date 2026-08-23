# AUTODEC-0003 — CI/CD and VPS Deploy

## Context
`WP-POC-002`'s real gate (`11-Handoff/WP-POC-002.md`) requires a public HTTPS
Webhook Endpoint that GREEN-API can call. The frozen baseline specifies Docker
Compose for local execution but does not specify how the running backend
becomes reachable from the public internet, nor how it is published from
GitHub to a real host.

## Gap
No repository existed on GitHub, no continuous deployment pipeline was
defined, and no reverse proxy/TLS termination was specified for the
already-provisioned Hostinger VPS with Docker/Compose installed and a domain
already pointed at it.

## Alternatives
- deploy manually via `scp`/`ssh` on every change, no automation;
- rebuild the image from source directly on the VPS (`git pull` + `docker
  compose up --build`), avoiding a registry;
- build and push an image to a container registry in CI, then have CI
  instruct the VPS to pull and restart via SSH;
- a managed PaaS instead of the existing VPS.

## Decision
- Publish the repository on GitHub as `shift-catcher` (private).
- Extend the existing `verify` GitHub Actions workflow with a `deploy` job
  that runs only on push to `main` after `verify` passes: build the Docker
  image, push it to GHCR (`ghcr.io/<owner>/shift-catcher`), copy
  `docker-compose.prod.yml`/`Caddyfile` to the VPS over SSH, and run
  `docker compose pull && up -d` there.
- Terminate TLS on the VPS with Caddy (automatic Let's Encrypt certificate for
  the domain already pointed at the VPS), reverse-proxying to the `app`
  service on the internal Compose network. The application itself keeps
  listening only on `8080` inside that network, never exposed directly.
- Deploy credentials (`VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`) are stored only as
  GitHub Actions repository secrets, never committed. The SSH keypair is
  generated on the VPS itself (`deploy/README.md`), so the private key never
  transits through chat, this repository, or any third party.
- `.env` on the VPS (real `DB_PASSWORD`, `ADMIN_API_TOKEN`, `DOMAIN`,
  `GREEN_API_*`) is created directly on the VPS and stays out of git, same
  pattern as local `.env` (`.gitignore` already excludes `.env*`).

## Rationale
Building in CI (not on the VPS) keeps the VPS free of the JDK/Gradle
toolchain and avoids rebuilding on constrained VPS resources. GHCR needs no
extra account. Caddy gives automatic, zero-maintenance TLS renewal, which a
POC-scale single VPS should not hand-roll with certbot cron jobs. SSH deploy
keeps the pipeline simple and matches a plan with a single environment (no
staging), consistent with the frozen POC's "single user, single instance"
scope (`00-Start/POC-Freeze.md`).

## Reversibility
HIGH

## Impact
None on domain code, `04-Domain`/`06-API`/`07-AI` specs, or the `WP-POC-*`
DAG. `docker-compose.prod.yml`, `Caddyfile`, and the `deploy` job in
`.github/workflows/ci.yml` are additive; local `compose.yaml` and
`scripts/gradle.ps1` verification are untouched. This closes part of
`WP-POC-002`'s "Minimal unblock action" (public HTTPS webhook), not the
GREEN-API instance/credentials/participant-confirmation portion, which
remains a real-gate input outside this repository's control.

## Evidence
- `11-Handoff/WP-POC-002.md` HARD_BLOCKER section lists "Public HTTPS Webhook
  Endpoint" among the unavailable real-gate inputs this closes.
- `.gitignore` already excludes `.env` and `.env.*` except `.env.example`,
  confirmed before adding `DOMAIN`/`ACME_EMAIL` documentation there.
- `SHA256SUMS.json` scope confirmed (2026-08-23) to cover only the 55-file
  frozen spec/doc baseline, not `Dockerfile`/`compose.yaml`/CI/scripts — new
  operational files here do not require checksum registration, matching how
  `AUTODEC-0001`/`AUTODEC-0002` were added without one.

## Amendment (2026-08-23)
The VPS hosts more than one project. During the first real deploy, the `app`
container failed to bind `127.0.0.1:8080` because an unrelated project on the
same VPS (`garimpo-zap-api-1`) already published that host port. Rather than
force-remove other projects' containers on every shift-catcher deploy,
`docker-compose.prod.yml` now publishes the app on `127.0.0.1:8081:8080`
(container-internal port unchanged at `8080`). The host-level Caddy config
(outside this repo, since `Caddyfile` was removed — see the entry above from
`b129f22`) must `reverse_proxy 127.0.0.1:8081` accordingly; this is a manual,
one-time step on the VPS itself, not something CI can apply.

## Status
ACTIVE
