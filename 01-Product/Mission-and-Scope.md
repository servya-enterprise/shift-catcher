# Mission and Scope

## Missão

Reduzir o tempo entre a publicação de um plantão elegível em um grupo do WhatsApp e a resposta `PEGO`, sem exigir monitoramento humano contínuo.

## Problema

Em grupos de "Pega-Pega", o recurso escasso é **tempo de reação**. A ferramenta deve:
- ouvir somente grupos permitidos;
- descartar conversa irrelevante;
- interpretar ofertas;
- aplicar regras objetivas;
- evitar conflitos;
- responder de forma idempotente;
- fornecer evidência de latência e decisão.

## POC

A POC prova:
1. viabilidade do canal;
2. confiabilidade do webhook;
3. resposta citada;
4. latência;
5. idempotência;
6. detecção inicial;
7. decisão manual e auto-claim controlado em teste.

## Usuário

Uma única usuária médica/operadora.

## Unidade de valor

Uma `ShiftOpportunity` corretamente detectada e, quando aceita, uma única resposta `PEGO` enviada ao grupo correto citando a mensagem correta.
