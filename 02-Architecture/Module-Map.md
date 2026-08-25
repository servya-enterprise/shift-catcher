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
- `reliability`: outbox/idempotência/retry/retenção.
- `observability`: métricas/audit.
- `console`: tela da operadora, renderizada no servidor.
- `webapp`: módulo Angular da operadora (`WP-MVP-005`, PLANNED) e a porta JSON que o serve.
- `benchmark`: replay do corpus rotulado para o gate do `WP-POC-008`.

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
webapp -> shift
webapp -> rules
webapp -> claim
webapp -> availability
webapp -> messaging
webapp -> group
benchmark -> detection
benchmark -> rules
benchmark -> messaging
rules -> claim
claim -> reliability
reliability -> integration.greenapi
```

Ciclos proibidos entre módulos de decisão.

`console` é uma segunda porta de entrada sobre os mesmos serviços, chamada em processo. Ela não
acrescenta operação em `/api/v1`, então o contrato de 42 operações continua intacto. O token fica no
servidor e o navegador recebe só a sessão — a tela renderiza texto escrito por terceiros e não pode
carregar credencial.

`webapp` é o sucessor dela e herda a mesma regra pelo mesmo motivo: o navegador fala com
`/console/api/*`, que reusa a sessão do console e chama os mesmos serviços em processo, e não com
`/api/v1`. Ampliar `/api/v1` para aceitar sessão de navegador poria autenticação de sessão e CSRF na
superfície que carrega o token de administração, e faria o navegador montar telas a partir de
chamadas de recurso — baixando todas as mensagens de terceiros para renderizar a citação de um card.
A porta devolve carga com o formato da tela. Alcançada como link no menu do Clara Care, nunca como
iframe ou módulo federado: `09-Decisions/AUTODEC-0009-Frontend-Embedding-Boundary.md`.

`benchmark` atravessa o pipeline sem gravar nada: `MessageAnalysisService.preview` não persiste,
e a avaliação usa uma oportunidade sintética que nunca chega ao banco. Um benchmark que criasse
claims responderia as ofertas reais que está medindo. Roda em thread própria, não no scheduler
compartilhado, porque uma inferência de minutos travaria os claims que ele existe para proteger.

A retenção **redige** a cadeia de mensagens em vez de apagá-la. `shift_claim` referencia
`shift_opportunity`, que referencia `incoming_message`, que referencia `incoming_provider_event`, e
um claim é o registro de uma mensagem que foi mesmo para um grupo. Apagar a linha também derrubaria
a chave de deduplicação: um webhook reentregue deixaria de ser reconhecido como duplicata. As linhas
ficam, as palavras saem — que é também a resposta melhor para quem escreveu naquele grupo e nunca
consentiu com nada.

`availability -> claim` lê os plantões já pegos direto de `shift_claim` em vez de espelhá-los:
um espelho ficaria desatualizado na primeira retratação (`EP-037`) e bloquearia uma data já
liberada. Fontes novas entram como beans de `CommitmentSourcePort` — é assim que a agenda
externa de `12-MVP/Calendar-Integration.md` entra sem tocar em `rules`.
