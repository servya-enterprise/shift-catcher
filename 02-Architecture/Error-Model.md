# Error Model

Usar `application/problem+json`.

Campos:
- type
- title
- status
- detail
- code
- correlationId
- instance

Códigos:
- `INVALID_REQUEST`
- `RESOURCE_NOT_FOUND`
- `CONFLICT`
- `STALE_VERSION`
- `GROUP_NOT_ALLOWED`
- `OPPORTUNITY_NOT_CLAIMABLE`
- `INSTANCE_NOT_OPERATIONAL`
- `GREEN_API_UNAVAILABLE`
- `QUOTE_MESSAGE_UNKNOWN`
- `AI_EXTRACTION_FAILED`
- `WEBHOOK_UNAUTHORIZED`

Secrets nunca aparecem em erro/log.
