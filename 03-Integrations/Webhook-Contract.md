# Webhook Contract

## Endpoint
`POST /api/v1/webhooks/green-api`

## Auth

Usar `webhookUrlToken` e `Authorization: Bearer`.
`401` para ausente/inválido.
IP allowlist oficial pode ser segunda barreira.

## Mensagem de grupo

Para `incomingMessageReceived` + `textMessage`, extrair:
- `instanceData.idInstance`
- `timestamp`
- `idMessage`
- `senderData.chatId`
- `senderData.chatName`
- `senderData.sender`
- `senderData.senderName`
- `senderData.senderContactName`
- `messageData.typeMessage`
- `messageData.textMessageData.textMessage`

## Request path

1. auth;
2. size/schema sanity;
3. dedupe/persist;
4. timestamp;
5. pending processing;
6. `200`.

Proibido chamar IA/rules/SendMessage dentro do request.

## Retry

Duplicata deve ser segura.
