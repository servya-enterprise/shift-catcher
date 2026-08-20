# GREEN-API Contract

## Plano
Developer na POC:
- uma instância;
- até 3 chats/grupos.

## Secrets
- `GREEN_API_API_URL`
- `GREEN_API_INSTANCE_ID`
- `GREEN_API_API_TOKEN`
- `GREEN_API_WEBHOOK_TOKEN`

## GetStateInstance

Normalizar:
- `authorized` -> operacional;
- `starting`/`sleepMode` -> degradado;
- `notAuthorized`/`blocked`/`suspended` -> não operacional.

Auto-claim somente `authorized`.

## Webhooks

Configurar:
- `webhookUrl`
- `webhookUrlToken`
- `incomingWebhook=yes`
- `outgoingAPIMessageWebhook=yes` opcional
- state/status webhooks quando úteis.

## SendMessage

Usar:
- `chatId`
- `message`
- `quotedMessageId`

Saída:
- `idMessage`

## Chat ID

Grupo termina em `@g.us`. Nunca gerar manualmente.

## Mock

Fake provider deve suportar success/4xx/5xx/timeout/invalid response e states.
