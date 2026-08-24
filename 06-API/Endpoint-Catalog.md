# Endpoint Catalog

Base: `/api/v1`

| EP | Method | Path | Purpose |
|---|---|---|---|
| EP-001 | GET | `/health` | Saúde da aplicação. |
| EP-002 | GET | `/poc/status` | Estado consolidado. |
| EP-003 | GET | `/metrics/latency` | P50/P95/P99. |
| EP-004 | POST | `/webhooks/green-api` | Receiver público autenticado. |
| EP-005 | GET | `/integrations/green-api/state` | Estado da instância. |
| EP-006 | POST | `/integrations/green-api/verify` | Verificação ativa. |
| EP-007 | GET | `/groups` | Lista allowlist. |
| EP-008 | POST | `/groups` | Registra group chatId. |
| EP-009 | GET | `/groups/{groupId}` | Detalha grupo. |
| EP-010 | PATCH | `/groups/{groupId}` | Edita flags. |
| EP-011 | POST | `/groups/{groupId}/enable` | Habilita. |
| EP-012 | POST | `/groups/{groupId}/disable` | Desabilita. |
| EP-013 | POST | `/groups/{groupId}/auto-claim/enable` | Habilita auto. |
| EP-014 | POST | `/groups/{groupId}/auto-claim/disable` | Desabilita auto. |
| EP-015 | GET | `/messages` | Lista mensagens. |
| EP-016 | GET | `/messages/{messageId}` | Detalha mensagem. |
| EP-017 | POST | `/messages/{messageId}/reprocess` | Reprocessa idempotente. |
| EP-018 | GET | `/opportunities` | Lista oportunidades. |
| EP-019 | GET | `/opportunities/{opportunityId}` | Detalha oportunidade. |
| EP-020 | POST | `/opportunities/{opportunityId}/review` | Revisão manual. |
| EP-021 | POST | `/opportunities/{opportunityId}/reevaluate` | Reaplica rules. |
| EP-022 | POST | `/opportunities/{opportunityId}/ignore` | Ignora. |
| EP-023 | POST | `/opportunities/{opportunityId}/claim` | Claim manual. |
| EP-024 | GET | `/claims` | Lista claims. |
| EP-025 | GET | `/claims/{claimId}` | Detalha attempts. |
| EP-026 | POST | `/claims/{claimId}/retry` | Retry manual seguro. |
| EP-027 | GET | `/rule-sets` | Lista versões. |
| EP-028 | POST | `/rule-sets` | Cria draft. |
| EP-029 | GET | `/rule-sets/{ruleSetId}` | Detalha. |
| EP-030 | PATCH | `/rule-sets/{ruleSetId}` | Edita draft. |
| EP-031 | POST | `/rule-sets/{ruleSetId}/activate` | Ativa versão. |
| EP-032 | POST | `/rule-sets/{ruleSetId}/simulate` | Simulação sem efeito. |
| EP-033 | POST | `/poc/send-test-reply` | Reply quoted controlada. |
| EP-034 | POST | `/poc/detect` | Detector/extractor sandbox. |
| EP-035 | POST | `/poc/benchmark/start` | Inicia benchmark. |
| EP-036 | GET | `/poc/benchmark/{benchmarkId}` | Resultado benchmark. |
| EP-037 | POST | `/claims/{claimId}/retract` | Retrata claim e apaga o PEGO enviado. |
| EP-038 | GET | `/settings/claim-message` | Texto da resposta configurado. |
| EP-039 | PUT | `/settings/claim-message` | Altera o texto da resposta. |
| EP-040 | GET | `/availability` | Compromissos: cadastrados + claims. |
| EP-041 | POST | `/availability` | Cadastra plantão obtido fora daqui. |
| EP-042 | DELETE | `/availability/{entryId}` | Remove compromisso cadastrado. |

**Total baseline: 42 operações HTTP.**

`EP-038` a `EP-042` são pós-POC (`12-MVP/MVP-Scope.md`, `WP-MVP-001`): ampliam o contrato de 37
para 42 operações deliberadamente e às claras. O padrão continua `PEGO` e a regra de agenda só
vale quando um rule set a configura, de modo que a evidência do `WP-POC-008` não muda.

A tela da operadora (`WP-MVP-002`) vive em `/console`, fora desta base, e chama os mesmos serviços
em processo. Ela não acrescenta nenhuma operação a este catálogo de propósito: é outra porta de
entrada para o mesmo produto, não produto novo.

Endpoints admin da POC podem ficar restritos a localhost/VPN. Se forem expostos publicamente, adicionar auth antes do deploy.
