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

Com a instância real autorizada, webhook público configurado e aplicação em execução, o probe controlado do WP-POC-002 espera uma mensagem nova no grupo exato, envia `PEGO`, testa replay idempotente e grava somente evidência sanitizada local:

```powershell
$env:ADMIN_API_TOKEN = '<token-local>'
.\scripts\invoke_wp_poc_002_real_probe.ps1 -ExpectedChatId '<grupo>@g.us'
```

O probe nunca converte aceitação HTTP em `GO`: a citação ainda exige confirmação visual de outro participante.

## Tela da operadora

`https://<seu-dominio>/console`, servida pela mesma aplicação. Entra-se uma vez com o
`ADMIN_API_TOKEN`; a partir daí o navegador carrega só a sessão, e o token nunca chega a uma página
que exibe mensagem escrita por terceiro. Um restart derruba as sessões e pede login de novo.

Ela não acrescenta endpoint: chama os mesmos serviços em processo. Ver `12-MVP/MVP-Scope.md` item 3.

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

## Deploy (VPS)

Pipeline de publicação contínua para a VPS (Hostinger, Docker/Compose já
instalado, domínio próprio já apontado): GitHub Actions builda e publica a
imagem em `ghcr.io/<owner>/shift-catcher` após o job `verify` passar na
`main`, copia `docker-compose.prod.yml`/`Caddyfile` para a VPS via SSH e
executa `docker compose up -d`. TLS é automático via Caddy/Let's Encrypt.

Runbook completo (geração da chave SSH de deploy, secrets do GitHub, `.env`
de produção): [[deploy/README]]. Decisão registrada:
[[09-Decisions/AUTODEC-0003-CICD-and-VPS-Deploy]].

Endpoint de webhook público, alvo da configuração da GREEN-API:
`POST https://<seu-dominio>/api/v1/webhooks/green-api`.
