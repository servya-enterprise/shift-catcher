# Risks and Operational Limits

## Natureza da integração

A POC usa GREEN-API, não a Meta WhatsApp Cloud API oficial. O serviço opera uma integração vinculada à conta WhatsApp e está sujeito ao lifecycle/estado dessa instância.

## Riscos que a POC deve medir

- `notAuthorized`: necessidade de novo vínculo;
- `blocked`/`suspended`: restrição da conta;
- `starting` prolongado;
- `sleepMode`;
- webhook atrasado;
- mensagem citada não conhecida pelo provider;
- Developer quota;
- mudança de comportamento do provider/WhatsApp.

## Política

Nenhum desses riscos é mascarado.

O sistema:
- registra estado;
- bloqueia auto-claim quando não operacional;
- mede indisponibilidade;
- não promete "nunca precisar QR";
- não usa o número de pacientes do Clara Care.

## Número dedicado

Recomendado para POC/produto:
- número próprio/dedicado ao uso de plantões;
- grupo de Pega-Pega separado de comunicação clínica.

## GO/NO-GO

Estabilidade de sessão é critério empírico. Após o POC funcional, manter teste por alguns dias e registrar reconexões antes de considerar operação contínua.
