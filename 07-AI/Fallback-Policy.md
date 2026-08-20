# AI Fallback Policy

Chamar IA somente se:
1. mensagem é candidate;
2. deterministic extraction deixou campo relevante ambíguo;
3. adapter está habilitado.

Não chamar para conversa comum ou mensagem já resolvida.

Mesmo com IA:
- hard rules continuam autoridade;
- ambiguous essential field bloqueia auto;
- medir latência/custo do parser separadamente.
