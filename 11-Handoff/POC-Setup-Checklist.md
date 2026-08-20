# POC Setup Checklist

## GREEN-API console

- [ ] Criar instância Developer.
- [ ] Conectar número dedicado.
- [ ] Confirmar `authorized`.
- [ ] Adicionar número ao grupo comum alvo.
- [ ] Escolher <= 3 chats permitidos.
- [ ] Configurar Webhook Endpoint público HTTPS.
- [ ] Configurar `webhookUrlToken` Bearer.
- [ ] Habilitar `incomingWebhook`.
- [ ] Habilitar status/state webhooks úteis.
- [ ] Guardar `apiUrl`, `idInstance`, token somente como secrets.

## Primeiro teste

- [ ] Outro participante envia "teste shift catcher".
- [ ] Backend recebe `incomingMessageReceived`.
- [ ] Confirmar `chatId @g.us`.
- [ ] Confirmar sender individual.
- [ ] Confirmar `idMessage`.
- [ ] Executar test reply com `quotedMessageId`.
- [ ] Ver `PEGO` citando a mensagem.

Se qualquer item crítico falhar, documentar antes de avançar.
