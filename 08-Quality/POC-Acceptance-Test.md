# POC Acceptance Test

## GREEN-API real
- [ ] Developer conectada.
- [ ] Número segue utilizável no celular conforme teste real.
- [ ] Grupo comum existente em allowlist.
- [ ] Mensagem de outro participante chega.
- [ ] `chatId` é grupo.
- [ ] sender individual existe.
- [ ] `idMessage` persistido.
- [ ] duplicate seguro.
- [ ] `PEGO` no grupo correto.
- [ ] `PEGO` cita a mensagem original.
- [ ] restart backend não exige novo pareamento.
- [ ] state monitorado.
- [ ] notAuthorized bloqueia claim.

## Pipeline
- [ ] irrelevante ignorada.
- [ ] oferta estruturada correta.
- [ ] ambígua => review/fallback.
- [ ] IA inválida não claim.
- [ ] claim atômico/idempotente.
- [ ] concorrência gera um send.
- [ ] retry não duplica.

## Performance
- [ ] benchmark.
- [ ] P50/P95/P99.
- [ ] P95 interno <=1s ou AUTODEC justificada.

Decisão final: `GO`, `GO_WITH_LIMITATIONS` ou `NO_GO`.
