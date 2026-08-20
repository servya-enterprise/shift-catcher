# WP-POC-001 Handoff

Status: DONE

## Scope
Independent Kotlin/Spring Boot foundation, PostgreSQL/Flyway, Testcontainers, build gates, and EP-001/EP-002 only.

## Evidence
- Frozen baseline checkpoint: `53d7002`.
- `scripts/gradle.ps1 verify --no-daemon`: PASS.
- 5 integration/HTTP tests: PASS against PostgreSQL 18.6 via Testcontainers.
- Flyway `V1__foundation.sql` applied to a clean PostgreSQL database: PASS.
- EP-001 authenticated health and EP-002 consolidated status: PASS.
- Missing admin Bearer returns `application/problem+json` with correlation ID: PASS.
- OpenAPI/YAML/JSON/DAG/wikilink/checksum/36-endpoint coverage validator: PASS.
- `docker build --tag shift-catcher:wp-poc-001 .`: PASS.
- Docker Compose configuration and live `UP/UP` health smoke on alternate host ports: PASS.
- Runtime image executes as UID 10001; builder/runtime base images are digest-pinned.
- No Clara Care runtime dependency can enter dependency resolution without failing the build.
- AUTODEC-0001 pins the foundation toolchain; AUTODEC-0002 defines the safe generic `INTERNAL_ERROR` Problem code.

## Residual risks
- GREEN-API transport is intentionally unverified until WP-POC-002.
- The host network cannot follow the Gradle wrapper's GitHub redirect and the physical Windows path is non-ASCII; `scripts/gradle.ps1` provides a checksum-pinned, same-repository fallback. Standard CI keeps the official wrapper.
- No real secret was used or committed; only ephemeral smoke-test values were injected into the process environment.
