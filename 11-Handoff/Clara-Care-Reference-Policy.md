# Clara Care Reference Policy

## Recomendação física

```text
development/
  clara-care/
  shift-catcher/
```

Não colocar `shift-catcher/` dentro de `clara-care/`.

## Como reaproveitar

As especificações deste pacote já extraem os padrões relevantes. O Codex não precisa ler o Clara Care para funcionar.

Se você desejar permitir referência humana:
- compare `AGENTS.md`, conventions e harness;
- copie padrões, não módulos de negócio;
- não faça import Gradle, DB link, shared schema ou HTTP dependency.

## Se usar Codex App

Abra `shift-catcher` como projeto próprio. Isso evita que o contexto do Clara Care V1 seja carregado em tarefas de plantão.
