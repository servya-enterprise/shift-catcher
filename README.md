# Shift Catcher — POC Specifications

Este pacote define a **POC independente** do Shift Catcher: um sistema de baixa latência para monitorar até três grupos do WhatsApp através do plano Developer da GREEN-API, detectar ofertas de plantão e responder à mensagem original com `PEGO` quando a oportunidade for aceita.

> [!important]
> Este projeto **não faz parte do Clara Care** e não deve ser adicionado ao roadmap, baseline, módulos ou banco do Clara Care. Ele reutiliza apenas **padrões de engenharia** que funcionaram bem naquele projeto.

Comece em [[00-Start/00-Home]].

## Princípio central

`grupo real -> GREEN-API -> webhook -> ingestão idempotente -> detecção -> oportunidade -> claim -> SendMessage quoted -> "PEGO" no grupo`

## Conteúdo

Somente especificações:
- escopo e critérios de sucesso;
- arquitetura;
- contrato GREEN-API;
- domínio e estados;
- catálogo HTTP da POC;
- draft OpenAPI;
- persistência lógica;
- detecção e fallback por IA;
- benchmark de latência;
- testes;
- decisões congeladas;
- work packages;
- prompt de bootstrap para Codex.

Não contém código de aplicação.
