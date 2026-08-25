# Benchmark Plan

Procedência do corpus é obrigatória (`REAL`, `SYNTHETIC`, `MIXED`) e sem valor padrão, porque a
resposta decide o que os números podem sustentar:

> Um corpus inventado pode **reprovar** este sistema, mas não pode **aprová-lo.** Ele mede as formas
> de escrever que quem o escreveu imaginou, não como as pessoas daquele grupo escrevem.

Só `REAL` é admissível como evidência de `GO`. Ver `08-Quality/corpus/`.

Dataset mínimo:
- 100 mensagens;
- >=30 candidates;
- >=20 ofertas estruturadas;
- >=10 ambíguas.

Cenários:
1. deterministic + manual claim;
2. deterministic + auto em grupo de teste;
3. AI fallback;
4. duplicate webhook;
5. provider transient failure;
6. backend restart;
7. burst.

Pass:
- zero duplicate claims;
- zero wrong-group claims;
- zero auto com campo essencial ambíguo;
- P95 interno determinístico <= 1s até provider accepted;
- quote real confirmado.

Reportar P50/P95/P99 + top outliers.

## Harness

`EP-035` inicia e `EP-036` devolve o relatório. O replay não persiste oportunidade nem claim e
não envia mensagem: um benchmark que respondesse as ofertas medidas pegaria plantões de verdade
num grupo de verdade.

O relatório **não** emite o veredito. `08-Quality/POC-Acceptance-Test.md` termina em `GO`,
`GO_WITH_LIMITATIONS` ou `NO_GO`, e essa decisão é de uma pessoa. Cada critério aparece como
`MET`, `NOT_MET` ou `NOT_MEASURABLE_HERE` — os três que um replay não alcança (latência até
provider-accepted, duplicidade sob concorrência, confirmação visual da citação) ficam listados
e sem resposta, porque critério ausente de relatório se lê como critério atendido.

O número que decide é `confidentlyWrong`: leituras sem nenhuma ambiguidade restante — que um
rule set permissivo deixaria responder sozinho — que discordam do corpus. Cada uma é um `PEGO`
enviado para um plantão que não era o que parecia.
