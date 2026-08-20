# Quoted Reply Contract

Request lógico:

```json
{
  "chatId": "<group>@g.us",
  "message": "PEGO",
  "quotedMessageId": "<incoming-idMessage>"
}
```

## Invariantes

- mesmo `chatId` da origem;
- mesmo provider message ID;
- default exato `PEGO`;
- um claim => um efeito lógico;
- 200 + `idMessage` => provider accepted, não prova de delivery final.

GREEN-API precisa conhecer a mensagem citada; `incomingWebhook` deve estar habilitado.

A validação POC real exige confirmação visual do quote no grupo.
