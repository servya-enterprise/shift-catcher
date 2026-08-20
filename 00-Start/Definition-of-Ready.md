# Definition of Ready

Um WP pode ir para `READY` somente quando:

- todas as dependências estão `DONE`;
- endpoints do WP estão mapeados;
- decisões congeladas aplicáveis foram lidas;
- entradas/saídas e invariantes estão definidas;
- critérios de aceite são verificáveis;
- testes obrigatórios estão enumerados;
- nenhum segredo real precisa existir para testes automatizados;
- integração externa possui mock/fake;
- qualquer lacuna restante é reversível e pode ser resolvida por AUTODEC.

A ausência de credencial GREEN-API não bloqueia development: testes usam fake server. A validação em grupo real é um gate separado de POC.
