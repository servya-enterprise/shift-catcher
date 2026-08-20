# Detection and Extraction

## Stage 1 — Fast Filter

Sinais:
- plantão/plantao/vaga/cobrir/cobertura/troco/escala;
- horários (`19-07`, `07 às 19`, `12h`, `24h`);
- valor (`R$`, `1.2k`);
- locais conhecidos.

Saída: `IGNORE` ou `CANDIDATE`.

## Stage 2 — Deterministic Extraction

Resolver:
- data;
- hoje/amanhã pelo timezone;
- início/fim;
- overnight/duração;
- valor BRL;
- local/cidade.

Não inventar ausente.

## Stage 3 — AI fallback

Somente candidato com campo relevante ambíguo.

## Campos essenciais para AUTO

- isShiftOffer true;
- data;
- início;
- fim/duração;
- localização quando hard rule;
- rules hard aprovadas;
- confidence mínima;
- instance operacional.

Se qualquer essencial ambíguo => review.
