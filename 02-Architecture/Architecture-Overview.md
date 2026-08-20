# Architecture Overview

## Forma

Monólito modular pequeno, um deploy e um PostgreSQL.

```text
GREEN-API
   |
   v
integration.greenapi
   |
   v
messaging.ingestion
   |
   +--> group allowlist
   v
detection
   v
extraction ----> ai fallback
   v
shift
   v
rules
   v
claim
   v
outbox
   v
integration.greenapi -> SendMessage(quotedMessageId)
```

## Webhook

O request não percorre o pipeline inteiro.

1. autenticar;
2. validar envelope;
3. deduplicar;
4. persistir;
5. registrar trabalho pendente;
6. retornar `200`.

## Banco

PostgreSQL é source of truth para mensagens, oportunidades, avaliações, claims, outbox e auditoria.

Sem broker na POC.

## Provider boundary

Domínio não conhece URL/token/DTO GREEN-API.

Portas mínimas:
- `WhatsAppMessageSender`
- `WhatsAppInstanceHealth`
- `IncomingWebhookTranslator`

## Segurança

- secrets em environment/secret store;
- webhook token separado do API token;
- endpoint público com Bearer;
- logs não imprimem secrets/payload integral por default.
