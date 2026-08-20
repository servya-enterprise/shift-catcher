# Source References

Consulta da documentação oficial GREEN-API em 20/08/2026.

## Fontes primárias

- Plans / Developer limit: https://green-api.com/en/docs/about-tariffs/
- Webhook Endpoint: https://green-api.com/en/docs/api/receiving/technology-webhook-endpoint/
- Incoming text message: https://green-api.com/en/docs/api/receiving/notifications-format/incoming-message/TextMessage/
- Incoming webhook types: https://green-api.com/en/docs/api/receiving/notifications-format/type-webhook/
- SendMessage: https://green-api.com/en/docs/api/sending/SendMessage/
- Chat ID: https://green-api.com/en/docs/api/chat-id/
- GetStateInstance: https://green-api.com/en/docs/api/account/GetStateInstance/
- Instance state tracking: https://green-api.com/en/docs/api/recommendations/instance-status-tracking/
- Working with incoming webhooks: https://green-api.com/en/docs/api/recommendations/working-with-incomming-webhooks/

## Fatos congelados da POC

- Developer: uma instância; interação/notificações limitadas a 3 chats (contatos ou grupos).
- Group chat IDs terminam em `@g.us`.
- Webhook Endpoint é escolhido por menor latência.
- `incomingWebhook=yes` é necessário para mensagem recebida.
- `webhookUrlToken` pode proteger o endpoint via `Authorization`.
- Mensagem de grupo informa `senderData.chatId`, remetente, `idMessage`, `timestamp` e texto.
- `SendMessage` aceita grupo e `quotedMessageId`.
- Para quote funcionar, GREEN-API precisa conhecer a mensagem original; incoming notifications devem estar habilitadas.
- Webhooks podem ser reenviados; ingestão deve ser idempotente.
