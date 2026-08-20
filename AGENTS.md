# Shift Catcher — Agent Instructions

Missão: implementar a POC definida neste repositório sem ampliar escopo.

Ordem de autoridade:
1. `00-Start/POC-Freeze.md`
2. `09-Decisions/DEC-*.md`
3. especificações de arquitetura/domínio/integração
4. `openapi/poc-openapi.yaml`
5. work package ativo
6. AUTODEC
7. implementação

Regras:
- Trabalhar somente em WP `READY`.
- Não introduzir dependência com Clara Care.
- Reutilizar padrões de engenharia descritos em `02-Architecture/Clara-Care-Reuse-Strategy.md`; não copiar regras clínicas, multi-tenancy ou módulos do Clara Care.
- GREEN-API fica atrás de uma porta de integração.
- Webhook deve persistir/deduplicar e responder rapidamente; não executar IA nem claim síncrono no request.
- IA interpreta mensagens ambíguas; não decide autonomamente aceitar um plantão.
- Fail-safe: ambiguidade relevante => `REVIEW_REQUIRED`, nunca `AUTO_CLAIM`.
- Toda resposta `PEGO` deve ser idempotente por oportunidade/mensagem origem.
- Não alterar `DEC-*`. Lacunas reversíveis podem ser fechadas por `AUTODEC` documentada.
- Antes de `DONE`, executar os gates do WP e atualizar cobertura/handoff.
