# Claim Engine

## Precondições

- opportunity `ELIGIBLE`;
- source message existe;
- group habilitado;
- instance `OPERATIONAL`;
- sem claim;
- não expirado;
- evaluation válida;
- AUTO exige auto enabled.

## Transaction

1. concurrency guard;
2. revalidar;
3. criar claim;
4. `ELIGIBLE -> CLAIM_PENDING`;
5. outbox `SEND_CLAIM`;
6. commit.

## Worker

Envia `PEGO` para source chat, quoted source message.
Registra attempt e latência.
Retry curto só em falha transitória.

## Concorrência

Dois claims simultâneos => um vencedor, um 409, uma mensagem lógica.
