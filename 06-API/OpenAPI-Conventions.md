# OpenAPI Conventions

- OpenAPI 3.1.
- Base `/api/v1`.
- JSON UTF-8.
- Problem Details.
- `X-Correlation-Id`.
- `Idempotency-Key` em claim e test reply.
- cursor pagination em messages/opportunities/claims.
- ETag/version em aggregates editáveis.
- webhook Bearer separado de admin.
- provider DTO não vaza ao domínio.
- ISO-8601 para instantes.
