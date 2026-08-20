# Audit and Observability

Registrar tempos:
- providerTimestamp
- webhookReceivedAt
- persistedAt
- detectionCompletedAt
- extractionCompletedAt
- evaluationCompletedAt
- claimDecidedAt
- outboxCreatedAt
- sendStartedAt
- providerAcceptedAt

Métricas:
- webhooks;
- duplicates;
- candidate rate;
- AI fallback rate;
- claims;
- failures/retries;
- instance state;
- P50/P95/P99.

Logs estruturados; sem tokens/URLs credentialed/payload integral por default.
