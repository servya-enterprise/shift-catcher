# Rule Engine

Resultados:
- `ELIGIBLE`
- `REJECTED`
- `REVIEW_REQUIRED`

Hard rules configuráveis:
- auto-claim global;
- auto-claim do grupo;
- confidence mínima;
- dias;
- faixa horária;
- duração máxima;
- valor mínimo;
- cidades;
- locais bloqueados;
- campos obrigatórios;
- idade máxima da mensagem;
- provider operacional;
- conflito de agenda (`agendaConflictPolicy`, `agendaConflictMode`).

Conflito de agenda (pós-POC, `12-MVP/MVP-Scope.md`):
- `agendaConflictPolicy` ausente => regra não aplicada, como todo campo opcional aqui;
- `REJECT` trata a colisão como fato; `REVIEW` entrega a decisão à operadora;
- `agendaConflictMode` `OVERLAPPING` (padrão) compara janelas, atravessando a meia-noite que
  cada plantão eventualmente cruza; `SAME_DAY` colide pela data apenas;
- janela ilegível dos dois lados na mesma data => `AGENDA_CONFLICT_UNCERTAIN` e revisão: não
  saber se cruzam não é saber que não cruzam;
- sem `shiftDate` a regra não roda => `REQUIRED_FIELD_MISSING`.

Os compromissos chegam prontos pela porta `availability`; o engine continua puro.

`RuleSet` versionado e imutável depois de ativo.

Erro ao avaliar hard rule => `REVIEW_REQUIRED`.
