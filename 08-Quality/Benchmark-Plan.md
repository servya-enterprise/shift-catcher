# Benchmark Plan

Dataset mínimo:
- 100 mensagens;
- >=30 candidates;
- >=20 ofertas estruturadas;
- >=10 ambíguas.

Cenários:
1. deterministic + manual claim;
2. deterministic + auto em grupo de teste;
3. AI fallback;
4. duplicate webhook;
5. provider transient failure;
6. backend restart;
7. burst.

Pass:
- zero duplicate claims;
- zero wrong-group claims;
- zero auto com campo essencial ambíguo;
- P95 interno determinístico <= 1s até provider accepted;
- quote real confirmado.

Reportar P50/P95/P99 + top outliers.
