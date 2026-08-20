# Latency SLO

Velocidade é requisito funcional.

## Métricas

- Provider to webhook = `webhook_received_at - provider_timestamp`
- Detection = `detection_completed_at - webhook_received_at`
- Decision = `claim_decided_at - webhook_received_at`
- Send request = `provider_send_accepted_at - claim_decided_at`
- Internal claim = `provider_send_accepted_at - webhook_received_at`

## Targets POC

Sem fallback IA:
- ingestão P95 < 100 ms;
- detection/extraction P95 < 100 ms;
- rules P95 < 50 ms;
- outbox create P95 < 50 ms;
- **webhook -> provider accepted P95 < 1.000 ms**.

Com IA:
- medir separadamente;
- auto-claim só se política de latência permitir.

Benchmark: P50/P95/P99 em >=100 mensagens.
