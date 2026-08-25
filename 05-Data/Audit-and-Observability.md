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

## Retenção

Nada era apagado. Duas formas, por razões diferentes:

- **Redação** na cadeia de mensagens (`incoming_provider_event`, `incoming_message`): o texto e a
  identidade de quem escreveu saem, a linha e a chave de dedupe ficam. Apagar quebraria a
  idempotência do webhook e derrubaria evidência de um `PEGO` que existe no mundo.
- **Deleção** do que não é referenciado por nada: `audit_event`, intenções de outbox já `DONE`,
  relatórios de benchmark antigos.

Padrão de conteúdo: 180 dias — o mais longo de propósito, porque o log de mensagens é de onde sai o
corpus real do `WP-POC-008`, e encurtar isso destrói material que não volta.

O passo roda em **dry-run por padrão**: conta, registra e não muda nada até alguém armá-lo. É o único
código do projeto que destrói dado.
