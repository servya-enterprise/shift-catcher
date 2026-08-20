# Clara Care Reuse Strategy

## Decisão

Shift Catcher é **repositório separado**.

## Razão

O Clara Care tem autopilot, roadmap e baseline em andamento. Outro produto dentro do repo pode:
- contaminar seleção de WPs;
- aumentar contexto;
- alterar validadores;
- induzir acoplamento;
- misturar risco operacional do WhatsApp comum com sistema médico.

## Reutilizar conceitualmente

- Kotlin/Spring Boot;
- JDK/toolchain corrente;
- Gradle conventions;
- PostgreSQL + Flyway;
- Testcontainers;
- modular monolith;
- Problem Details;
- Correlation ID;
- outbox;
- idempotency ledger;
- audit append-only;
- OpenAPI;
- ktlint;
- gate `verify`;
- WPs + DoR/DoD;
- DEC/AUTODEC;
- ports/adapters;
- health/observability;
- testes reais de concorrência.

## Não copiar

- multi-tenancy/RLS tenant;
- patient/clinical/careplan;
- portal;
- consentimentos;
- billing;
- catálogo de 436 endpoints;
- Angular antes de necessário;
- SaaS.

## Regra

Pode reproduzir padrões, nunca dependency de runtime entre repositórios.
