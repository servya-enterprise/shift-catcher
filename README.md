# Shift Catcher — POC

POC independente e de baixa latência para monitorar até três grupos do WhatsApp através do plano Developer da GREEN-API, detectar ofertas de plantão e responder à mensagem original com `PEGO` quando a oportunidade for aceita.

> [!important]
> Este projeto **não faz parte do Clara Care** e não deve ser adicionado ao roadmap, baseline, módulos ou banco do Clara Care. Ele reutiliza apenas **padrões de engenharia** que funcionaram bem naquele projeto.

As especificações congeladas começam em [[00-Start/00-Home]]. O estado operacional está em [[11-Handoff/Execution-State]].

## Estado

- `WP-POC-001`: DONE.
- `WP-POC-002`: READY.
- Transporte GREEN-API real: **NOT_VERIFIED**. Nenhum teste fake conta como evidência desse gate.

## Verificação local

```powershell
.\scripts\gradle.ps1 verify --no-daemon
```

Em ambientes sem a limitação de path/rede do host Windows, use o wrapper padrão:

```bash
./gradlew verify --no-daemon
```

Para executar via Compose, copie `.env.example` para `.env`, substitua apenas valores locais e rode `docker compose up --build`. Credenciais reais nunca devem ser commitadas.

## Princípio central

`grupo real -> GREEN-API -> webhook -> ingestão idempotente -> detecção -> oportunidade -> claim -> SendMessage quoted -> "PEGO" no grupo`

## Baseline especificada

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
