# Corpus

## `synthetic-v1.json` — 100 mensagens inventadas

**Não é evidência de `GO`.** O harness marca isso em toda execução (`provenance: SYNTHETIC`,
`admissibleAsGoEvidence: false`), e a razão é uma assimetria, não formalidade:

> Um corpus inventado pode **reprovar** este sistema, mas não pode **aprová-lo.** Ele mede as formas
> de escrever que quem escreveu o corpus imaginou — não como as pessoas daquele grupo escrevem.

Serve como piso de regressão e como detector de `NO_GO`. Deve ser substituído por texto real do log
de mensagens conforme ele acumula: um caso aceita `messageId` no lugar de `text`.

Local e cidade **não** são afirmados nos rótulos. Dependem de `DETECTION_KNOWN_LOCATIONS` e
`DETECTION_KNOWN_CITIES`, então pontuá-los mediria o deploy, não o parser.

## Execução de 2026-08-25 (IA desligada, `knownLocations` vazio)

| | |
|---|---|
| Detecção | precisão 0,87 · recall 0,92 (46 TP, 7 FP, 43 TN, 4 FN) |
| Data | 32/35 corretas, 3 não lidas, 0 erradas |
| Horários | 27/35 corretos, 7 não lidos, 1 **errado** |
| Valor | 13/32 corretos, 19 não lidos, 0 errados |
| Auto-claim com campo ambíguo | **0** — o fail-safe do `DEC-005` segurou tudo |
| Contradições em leitura confiante | **1** |
| Campos não lidos em leitura confiante | 15 |

### O que isso levanta

Três padrões estruturais, que valem checar contra mensagens reais **antes** de mexer em código:

1. **Valor sem `R$` não é lido.** "paga 1400", "1.100", "R$1.600" — o último funciona, os dois
   primeiros não. É o grosso dos 19 não lidos. Enquanto nenhuma regra depender de `minAmount`, é
   inofensivo; no instante em que depender, deixa de ser: `minAmount` não protege de um valor que
   nunca foi lido.
2. **Oferta sem a palavra "plantão" passa em branco.** `offer-08`, `offer-23` e `offer-29` são
   ofertas completas ("31/08 das 19 as 7 alguém? paga 1400") que o detector não marcou.
3. **Uma contradição de horário:** `offer-11`, "plantao 03/09 das 7 ate as 19h" — sem acento e com
   "ate" no lugar de "às". Essa é a classe perigosa: horário lido errado com confiança é um `PEGO`
   enviado para o plantão errado.

Os 7 falsos positivos são conversa que menciona plantão ou escala ("quem tá de plantão hoje?").
Custam uma revisão, não um claim errado.

### Não ajuste o parser para este arquivo

Duas razões, e a segunda é a que pesa:

- Ajustar a um corpus inventado otimiza para a imaginação de quem o escreveu.
- Mexer em detecção ou extração antes de o `WP-POC-008` rodar move a linha de base do próprio gate.
  A ordem correta é: corpus real primeiro, medida depois, mudança por último.
