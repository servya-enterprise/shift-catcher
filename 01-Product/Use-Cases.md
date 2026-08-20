# Use Cases

## UC-01 — Receber mensagem de grupo permitido
Mensagem de texto chega via GREEN-API, é normalizada e persistida.

## UC-02 — Ignorar grupo não permitido
`chatId` fora da allowlist nunca cria oportunidade.

## UC-03 — Detectar candidato
Mensagem com sinais de oferta segue para extração; conversa comum vira `IGNORED`.

## UC-04 — Extrair oferta
Extrair data, início, fim, local, cidade, valor e observações. Ambiguidade pode acionar IA.

## UC-05 — Revisão manual
Oportunidade incompleta/ambígua fica `REVIEW_REQUIRED`, sem enviar WhatsApp.

## UC-06 — Claim manual
Operador aciona endpoint; sistema revalida e envia `PEGO` citando origem.

## UC-07 — Auto-claim
Somente com hard rules satisfeitas e feature explicitamente habilitada.

## UC-08 — Retry seguro
Falha transitória pode repetir request sem duplicar efeito lógico.

## UC-09 — Estado da instância
Estado não operacional bloqueia auto-claim.

## UC-10 — Benchmark
Registrar tempos provider/webhook/detecção/decisão/envio.
