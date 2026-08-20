# Transactionality and Idempotency

## Webhook
Chave lógica:
`GREEN_API:{idInstance}:{typeWebhook}:{idMessage}`

## Claim
`SHIFT_CLAIM:{opportunityId}`

Somente uma transação move `ELIGIBLE -> CLAIM_PENDING`.

## Outbox

Claim state + `SEND_CLAIM_MESSAGE` na mesma transação.

## Retry

Somente erro transitório, mantendo chat/quote/texto.

Budget inicial:
`0ms, 150ms, 400ms, 800ms, 1500ms`.

Depois: `FAILED`.

## Duplicata

Webhook duplicado retorna sucesso e não repete efeitos.

## Crash

Mensagem/outbox persistidas são retomáveis. Claim aceito nunca volta automaticamente a elegível.
