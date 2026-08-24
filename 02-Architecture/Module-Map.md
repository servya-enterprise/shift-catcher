# Module Map

- `integration.greenapi`: adapter HTTP, DTOs provider, health, webhook translation.
- `messaging`: ingestão, dedupe, mensagem normalizada.
- `group`: allowlist/configuração.
- `detection`: filtro rápido.
- `extraction`: parsing determinístico + fallback orchestration.
- `ai`: porta de parser IA.
- `shift`: aggregate oportunidade.
- `rules`: elegibilidade/auto-claim.
- `availability`: compromissos já assumidos, de fontes plurais (`CommitmentSourcePort`).
- `claim`: transação/tentativas.
- `reliability`: outbox/idempotência/retry.
- `observability`: métricas/audit.
- `console`: tela da operadora, renderizada no servidor.

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
availability -> claim
console -> shift
console -> rules
console -> claim
console -> availability
console -> messaging
rules -> claim
claim -> reliability
reliability -> integration.greenapi
```

Ciclos proibidos entre módulos de decisão.

`console` é uma segunda porta de entrada sobre os mesmos serviços, chamada em processo. Ela não
acrescenta operação em `/api/v1`, então o contrato de 42 operações continua intacto. O token fica no
servidor e o navegador recebe só a sessão — a tela renderiza texto escrito por terceiros e não pode
carregar credencial.

`availability -> claim` lê os plantões já pegos direto de `shift_claim` em vez de espelhá-los:
um espelho ficaria desatualizado na primeira retratação (`EP-037`) e bloquearia uma data já
liberada. Fontes novas entram como beans de `CommitmentSourcePort` — é assim que a agenda
externa de `12-MVP/Calendar-Integration.md` entra sem tocar em `rules`.
