# POC Acceptance Test

Reconciliado em 2026-08-25 contra a evidência registrada. Cada caixa marcada cita de onde vem; cada
caixa aberta diz o que falta. Nenhuma foi marcada por parecer verdadeira.

Duas fontes valem distinguir. **Observação real** é algo que aconteceu contra a instância em
produção e está registrado em `11-Handoff/WP-POC-002.md` ou `11-Handoff/Execution-State.md`.
**Teste** é uma propriedade do código provada por suíte automatizada — vale para o comportamento,
não para o mundo.

## GREEN-API real

- [x] Developer conectada. — real: instância `710722715723`, `providerState=AUTHORIZED` via `EP-006`.
- [ ] Número segue utilizável no celular conforme teste real. — **sem evidência.** O número é
  dedicado, mas ninguém registrou tê-lo usado normalmente no aparelho depois do pareamento. É
  observação de dias, não de minutos.
- [x] Grupo comum existente em allowlist. — real: "Plantões Medicina", `120363429389915786@g.us`.
- [x] Mensagem de outro participante chega. — real: enviada de `5514998943823@c.us` e ingerida.
- [x] `chatId` é grupo. — real: `120363429389915786@g.us`.
- [x] sender individual existe. — real: `5514998943823@c.us`.
- [x] `idMessage` persistido. — real: `AC7A8113482BAD3CFA0E93BADAB7FBB6`.
- [x] duplicate seguro. — real: segunda chamada devolveu `idempotentReplay=true` sem nova mensagem.
  Teste: `webhook persists exact transport identifiers and deduplicates retry`.
- [x] `PEGO` no grupo correto. — real: confirmação visual do operador no WhatsApp.
- [x] `PEGO` cita a mensagem original. — real: confirmação visual. A API nunca afirma isso sobre si
  mesma, por desenho (`verify never infers visual quote success from provider acceptance`).
- [x] restart backend não exige novo pareamento. — real: `docker compose restart app` e o replay
  seguiu devolvendo o mesmo `providerMessageId`, com a instância ainda `AUTHORIZED`.
- [x] state monitorado. — `EP-005`, tabela `provider_health` e observação agendada.
- [x] notAuthorized bloqueia claim. — teste: `a non operational instance blocks the claim before
  anything is written` e `non operational state blocks send before provider effect`.

## Pipeline

- [x] irrelevante ignorada. — teste: `ordinary conversation is processed without creating an
  opportunity`.
- [x] oferta estruturada correta. — teste: `a complete offer becomes an opportunity waiting for the
  rule engine`, mais `ShiftExtractorTest`.
- [x] ambígua => review/fallback. — teste: `an ambiguous offer waits for a human instead of
  guessing`; e o fail-safe do `DEC-005` vale nas 100 mensagens de `08-Quality/corpus/`.
- [x] IA inválida não claim. — teste: `an invalid model answer falls back to review`, `a model
  failure never breaks ingestion`, `the model may not terminate an opportunity on its own`.
  **Ressalva:** provado contra um parser falso. Nenhum adaptador real está ligado a
  `AiShiftParserPort`, então a IA nunca foi exercida de ponta a ponta.
- [x] claim atômico/idempotente. — teste: `deciding a claim writes the intent without sending
  anything`, `claiming twice is a conflict`.
- [x] concorrência gera um send. — teste: `concurrent claims leave one winner and one logical send`.
- [x] retry não duplica. — teste: `a manual retry reuses the same claim and sends once`, `draining
  the outbox twice does not send twice`.

## Performance

- [ ] benchmark. — o harness existe (`EP-035`/`EP-036`) e nunca rodou sobre corpus real. A única
  execução foi sobre corpus **inventado**, que por construção não sustenta `GO`
  (`08-Quality/corpus/README.md`).
- [ ] P50/P95/P99. — `EP-003` calcula, mas a produção tem **um** claim real. Um percentil sobre uma
  amostra é o próprio número com outro nome.
- [ ] P95 interno <=1s ou AUTODEC justificada. — o claim real levou 340 ms entre decisão e aceite do
  provider, dentro do orçamento; isso é uma medida, não um P95.

## O que falta para a decisão

Três coisas, e nenhuma é código:

1. Corpus real rotulado (`08-Quality/Benchmark-Plan.md`: 100 / ≥30 / ≥20 / ≥10).
2. Volume de claims reais suficiente para um percentil significar algo.
3. Dias de uso do número no aparelho, para a caixa de estabilidade de sessão.

Decisão final: `GO`, `GO_WITH_LIMITATIONS` ou `NO_GO`. **Não asserida.** O harness relata fatos por
critério e não computa veredito: essa decisão é de uma pessoa.
