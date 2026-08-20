# Data Model

Tabelas:
- `allowed_group`
- `incoming_provider_event`
- `incoming_message`
- `detection_result`
- `shift_opportunity`
- `rule_set`
- `rule_evaluation`
- `shift_claim`
- `claim_attempt`
- `outbox_event`
- `audit_event`

## IDs
UUIDv7.

## Time
Instantes UTC; interpretação local `America/Sao_Paulo`.

## Constraints

- allowed group unique provider chat;
- provider event/message dedupe;
- one opportunity/source message na POC;
- one claim/opportunity.

## No hard delete
Mensagens/oportunidades/claims preservadas na POC.
