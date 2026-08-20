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
- provider operacional.

`RuleSet` versionado e imutável depois de ativo.

Erro ao avaliar hard rule => `REVIEW_REQUIRED`.
