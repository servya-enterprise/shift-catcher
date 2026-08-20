# Module Map

- `integration.greenapi`: adapter HTTP, DTOs provider, health, webhook translation.
- `messaging`: ingestão, dedupe, mensagem normalizada.
- `group`: allowlist/configuração.
- `detection`: filtro rápido.
- `extraction`: parsing determinístico + fallback orchestration.
- `ai`: porta de parser IA.
- `shift`: aggregate oportunidade.
- `rules`: elegibilidade/auto-claim.
- `availability`: porta opcional de disponibilidade.
- `claim`: transação/tentativas.
- `reliability`: outbox/idempotência/retry.
- `observability`: métricas/audit.

## Dependências

```text
integration.greenapi -> messaging
messaging -> group
messaging -> detection
detection -> extraction
extraction -> ai
extraction -> shift
shift -> rules
rules -> availability
rules -> claim
claim -> reliability
reliability -> integration.greenapi
```

Ciclos proibidos.
