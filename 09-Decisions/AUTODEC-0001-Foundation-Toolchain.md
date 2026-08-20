# AUTODEC-0001 — Foundation Toolchain

## Context
WP-POC-001 requires the current engineering toolchain without coupling this repository to Clara Care. The extracted baseline intentionally does not freeze concrete dependency versions, and the reference policy says that reading another repository is unnecessary.

## Gap
The repository did not specify exact JDK, Spring Boot, Kotlin, Gradle, ktlint, or PostgreSQL image versions.

## Alternatives
- inspect or depend on another repository;
- use preview releases;
- select supported stable versions from the local JDK and official project metadata.

## Decision
Use JDK 21, Spring Boot 4.1.0, Kotlin 2.3.21, Gradle Wrapper 9.5.1, ktlint Gradle plugin 14.2.0 with ktlint 1.8.0, and PostgreSQL 18.6 for the foundation.

The Docker build uses the digest-pinned Gradle 8.14.5/JDK 21 image because Spring Boot 4.1 supports Gradle 8.14+ and this avoids downloading a wrapper distribution during an isolated container build. The runtime uses a digest-pinned Temurin 21 JRE Alpine image and a non-root UID.

The wrapper retains Gradle's official distribution URL and pins the SHA-256 published by Gradle. On this Windows bootstrap host, the official redirect to GitHub timed out in the wrapper and the physical repository path contains non-ASCII characters that break the Gradle test-worker argfile. `scripts/gradle.ps1` therefore downloads the same checksum-pinned distribution through an accessible mirror and executes the same repository through a local ASCII junction. CI and ordinary environments keep using the standard wrapper.

## Rationale
JDK 21 is the installed LTS toolchain. Spring Initializr 4.1.0 emits Kotlin 2.3.21 and Gradle 9.5.1 for JDK 21. All choices are stable, project-local, wrapper-pinned, and compatible with the frozen architecture.

## Reversibility
HIGH

## Impact
The build requires no global Gradle installation. Toolchain upgrades are isolated to build metadata and container tags.

## Evidence
- Local bootstrap: Temurin JDK 21.0.11, Docker 29.5.2, Compose 5.1.4.
- Spring Initializr metadata and generated build for Spring Boot 4.1.0 on 2026-08-20.
- Official Gradle and Kotlin compatibility/release documentation reviewed on 2026-08-20.
- Official Gradle 9.5.1 binary SHA-256: `bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f`.
- `scripts/gradle.ps1 verify --no-daemon` exercises the Windows fallback without creating a second worktree.

## Status
ACTIVE
