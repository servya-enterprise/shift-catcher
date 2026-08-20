# POC Freeze

Status: **FROZEN**

## Objetivo

Provar que um número WhatsApp conectado à GREEN-API consegue, em um **grupo comum já existente**:

1. receber uma mensagem de texto do grupo via Webhook Endpoint;
2. identificar o `chatId`, remetente, `idMessage`, timestamp e texto;
3. persistir a mensagem de maneira idempotente;
4. classificá-la como candidata ou não a oferta de plantão;
5. representar uma oferta como `ShiftOpportunity`;
6. aceitar a oportunidade manualmente e, opcionalmente, por regra automatizada segura;
7. responder `PEGO` **citando a mensagem original**;
8. registrar tempos de cada etapa para medir latência;
9. sobreviver a restart do nosso backend sem requerer novo pareamento da GREEN-API;
10. detectar estado `notAuthorized`, `blocked`, `starting`, `sleepMode` ou `suspended`.

## Escopo técnico congelado

- Projeto independente.
- Kotlin.
- Spring Boot.
- JDK igual à baseline corrente do Clara Care no bootstrap; se estiver em JDK 25, usar JDK 25.
- PostgreSQL.
- Flyway.
- Testcontainers.
- REST + Webhook Endpoint.
- GREEN-API Developer para POC.
- Sem frontend obrigatório.
- Sem SaaS.
- Sem multi-tenancy.
- Uma única pessoa usuária.
- Uma única instância GREEN-API.
- Até três chats/grupos por limitação do plano Developer.
- Mensagens de texto apenas no caminho automático da POC.
- Resposta padrão: `PEGO`.
- Outbox para efeito externo de envio.
- IA somente como fallback de interpretação.
- Nenhuma integração com banco/API do Clara Care.

## Critério de não ampliação

Qualquer feature que não seja necessária para provar o fluxo acima fica para pós-POC.
