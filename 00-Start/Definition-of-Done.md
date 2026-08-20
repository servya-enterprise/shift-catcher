# Definition of Done

Um WP só é `DONE` quando:

- implementação está compilando;
- migrations aplicam em PostgreSQL limpo;
- testes do WP passam;
- regressão pertinente passa;
- contrato OpenAPI continua válido quando o WP expõe HTTP;
- idempotência/concorrência foram testadas onde aplicável;
- nenhum segredo foi commitado;
- nenhuma integração real foi acoplada ao domínio;
- endpoint coverage está atualizado;
- AUTODEC usada foi documentada;
- handoff do WP contém evidências e riscos residuais.

A POC global somente é `PASSED` conforme [[../08-Quality/POC-Acceptance-Test]].
