# Deploy — VPS (produção)

Runbook operacional para publicar o Shift Catcher em uma VPS via Docker Compose,
com HTTPS automático (Caddy) e deploy contínuo pelo GitHub Actions.

Este runbook existe para satisfazer um input do gate real do `WP-POC-002`
(`11-Handoff/WP-POC-002.md`): um Webhook Endpoint HTTPS público que a GREEN-API
consiga chamar. Nada aqui altera o escopo congelado da POC
(`00-Start/POC-Freeze.md`) — é infraestrutura de publicação, não domínio/negócio.
Ver `09-Decisions/AUTODEC-0003-CICD-and-VPS-Deploy.md` para a decisão registrada.

## 0. Pré-requisitos

- VPS com Docker + Docker Compose plugin instalados (`docker compose version`).
- Domínio com registro DNS tipo A apontando para o IP público da VPS.
- Portas 80 e 443 liberadas no firewall da VPS (Hostinger + `ufw`/`iptables`, se houver).
- Um usuário Linux não-root na VPS com permissão de usar `docker` (grupo `docker`).

## 1. Gerar a chave SSH de deploy (rodar NA VPS, não localmente)

Gerar a chave diretamente na VPS evita que a chave privada trafegue por qualquer
lugar além do par VPS <-> GitHub.

```bash
ssh-keygen -t ed25519 -N "" -C "shift-catcher-deploy" -f ~/.ssh/shift_catcher_deploy
cat ~/.ssh/shift_catcher_deploy.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
cat ~/.ssh/shift_catcher_deploy   # copiar este conteúdo INTEIRO para o secret VPS_SSH_KEY no GitHub
```

No GitHub: `Settings -> Secrets and variables -> Actions -> New repository secret`,
criar três secrets:

- `VPS_HOST`: IP ou hostname da VPS.
- `VPS_USER`: o usuário Linux criado acima.
- `VPS_SSH_KEY`: conteúdo completo da chave privada `shift_catcher_deploy` (com as
  linhas `-----BEGIN...-----` e `-----END...-----`).

## 2. Autenticar a VPS no GitHub Container Registry (uma vez)

O repositório é privado, então o pacote publicado em `ghcr.io` herda visibilidade
privada por padrão. Para a VPS conseguir `pull` sem expor um secret adicional no
workflow, autentique o Docker da VPS uma única vez com um token pessoal com escopo
`read:packages` (Settings -> Developer settings -> Personal access tokens):

```bash
docker login ghcr.io -u SEU_USUARIO_GITHUB -p ghp_xxx_read_packages_token
```

Alternativa mais simples: depois do primeiro push, em
`github.com/<usuario>/shift-catcher/pkgs/container/shift-catcher -> Package settings`,
trocar a visibilidade do pacote para "Public" (o repositório continua privado; só a
imagem Docker, que não contém segredos, fica pública). Nesse caso o passo acima é
dispensável.

## 3. Preparar o diretório e o `.env` de produção (uma vez, na VPS)

```bash
mkdir -p ~/shift-catcher
cd ~/shift-catcher
```

Criar `~/shift-catcher/.env` (nunca commitar este arquivo) com, no mínimo:

```env
DB_NAME=shift_catcher
DB_USER=shift_catcher
DB_PASSWORD=<senha-forte-gerada-localmente>
ADMIN_API_TOKEN=<token-admin-forte-gerado-localmente>
DOMAIN=<seu-dominio.com>
ACME_EMAIL=<seu-email>
GREEN_API_API_URL=
GREEN_API_INSTANCE_ID=
GREEN_API_API_TOKEN=
GREEN_API_WEBHOOK_TOKEN=
```

Os quatro `GREEN_API_*` ficam vazios até a Etapa GREEN-API (ver README principal)
ser concluída; a aplicação sobe normalmente com o transporte GREEN-API
`UNCONFIGURED`.

## 4. Primeiro deploy

O workflow `.github/workflows/ci.yml` (job `deploy`) roda automaticamente a cada
push na `main` que passar no job `verify`. Ele builda a imagem, publica em
`ghcr.io/<owner>/shift-catcher`, copia `docker-compose.prod.yml` e `Caddyfile`
para `~/shift-catcher` na VPS via SSH, e executa:

```bash
docker compose --env-file .env --env-file .image.env -f docker-compose.prod.yml pull
docker compose --env-file .env --env-file .image.env -f docker-compose.prod.yml up -d --remove-orphans
```

Para forçar um primeiro deploy manual (sem esperar o Actions), com a imagem já
publicada em `ghcr.io`:

```bash
cd ~/shift-catcher
echo "IMAGE=ghcr.io/<owner>/shift-catcher:latest" > .image.env
docker compose --env-file .env --env-file .image.env -f docker-compose.prod.yml up -d
```

## 5. Verificação

```bash
curl -I https://<seu-dominio.com>
curl -H "Authorization: Bearer $ADMIN_API_TOKEN" https://<seu-dominio.com>/api/v1/health
```

O endpoint de webhook que a GREEN-API vai chamar é:

```
POST https://<seu-dominio.com>/api/v1/webhooks/green-api
Authorization: Bearer <GREEN_API_WEBHOOK_TOKEN>
```

## Rollback

```bash
cd ~/shift-catcher
echo "IMAGE=ghcr.io/<owner>/shift-catcher:<sha-anterior-bom>" > .image.env
docker compose --env-file .env --env-file .image.env -f docker-compose.prod.yml up -d
```

O Postgres roda no mesmo host via volume nomeado; nenhuma migração é revertida
automaticamente. Migrações Flyway são apenas forward-only (padrão do projeto).
