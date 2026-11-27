# Dtmoney - Backend - RocketSeat

Este é o backend do projeto **dtmoney**.

## 🚀 Tecnologias

- **Node.js**
- **TypeScript**
- **TypeORM**
- **SQLite**

## 📦 Instalação e Execução

Siga os passos abaixo para configurar e rodar o projeto em seu ambiente local.

### 1️⃣ Clonar o repositório

```sh
git clone https://github.com/arthur-fabricyo/dt-money-app.git
cd dt-money-app/DT-money-backend
```

### 2️⃣ Instalar as dependências

Utilize o gerenciador de pacotes **yarn** ou **npm** para instalar todas as dependências do projeto:

```sh
npm install
```

### 3️⃣ Configurar a chave da API

Na pasta do backend, crie um `.env` local com uma chave JWT aleatória. O comando abaixo cria o arquivo com a variável documentada em `.env.example` e preserva um `.env` que já exista.

```sh
node -e "require('node:fs').writeFileSync('.env', 'APP_SCRETET_KEY=' + require('node:crypto').randomBytes(32).toString('hex') + '\n', { flag: 'wx' })"
```

Em cada ambiente implantado, configure `APP_SCRETET_KEY` no gerenciador de segredos da hospedagem. A rotação da chave invalida os JWTs assinados com a chave anterior.

### 4️⃣ Rodar as migrations

Execute o comando abaixo para criar as tabelas no banco de dados:

```sh
npx typeorm-ts-node-commonjs migration:run -d src/infra/database/typeorm/dt-money/data-source.ts
```

### 5️⃣ Iniciar o servidor

Agora, basta rodar o servidor com:

```sh
npm run dev
```

Por padrão, a API e sua documentação ficam em `http://localhost:3001` e `http://localhost:3001/docs`.

Para testar em um celular na mesma rede Wi‑Fi, coloque o IPv4 da interface Wi‑Fi do computador em `DT_MONEY_HOST` no `.env` e reinicie o servidor. A API passa a escutar nesse endereço, na porta `3001`. Confira a URL de login e o teste de conexão no [guia do Android](../dt-money-android/README.md#celular-físico-por-wi-fi-sem-usb).

## Sincronização Android com Turso e backup no R2

O Android usa o SQLite do aparelho como banco principal e sincroniza somente quando a conta está conectada. O backend hospedado persiste contas, categorias, transações, revisões e recibos de mutação no Turso usando `@libsql/client`. Os esquemas desse modo são criados na inicialização; as migrations TypeORM acima pertencem ao modo legado.

Use Node.js **22.23 ou superior**. A execução atual carrega TypeScript com `tsx`, que está nas dependências de desenvolvimento; inclua essas dependências também na hospedagem:

```sh
npm ci --include=dev
npm start
```

Configure no gerenciador de segredos do servidor os valores indicados em [.env.example](.env.example). Exemplo de estrutura, com valores que devem ser substituídos:

```dotenv
APP_SECRET_KEY=<chave-aleatoria-com-pelo-menos-32-caracteres>
TURSO_DATABASE_URL=libsql://<banco-libsql-de-teste>.turso.io
TURSO_AUTH_TOKEN=<token-do-banco-de-teste>
BACKUP_TRIGGER_TOKEN=<token-aleatorio-do-agendador>
BACKUP_AGE_RECIPIENT=age1<destinatario-publico>
R2_ACCOUNT_ID=<id-da-conta-cloudflare>
R2_BUCKET=dt-money-backups-test
R2_ACCESS_KEY_ID=<credencial-de-objetos-do-bucket>
R2_SECRET_ACCESS_KEY=<segredo-da-credencial-de-objetos>
R2_BACKUP_PREFIX=dt-money/test/backups/
```

`APP_SECRET_KEY` é a chave do modo de sincronização. `APP_SCRETET_KEY`, com a grafia histórica, continua sendo usada somente pela API legada. Gere as chaves fora do repositório, mantenha os valores reais no gerenciador de segredos e use credenciais próprias e dados fictícios no ambiente de teste. O APK recebe somente a URL da API e a sessão do usuário; tokens Turso, R2 e do agendador ficam no servidor.

A presença de `TURSO_DATABASE_URL` ativa o modo de sincronização. A URL precisa usar `libsql://` ou `https://` e ter `TURSO_AUTH_TOKEN`. Esse modo oferece:

| Rota | Acesso e função |
| --- | --- |
| `GET /health` | Verificação pública de disponibilidade. |
| `POST /auth/register` | Cadastro com `name`, `email` e `password`; responde com usuário e JWT. |
| `POST /auth/login` | Login com `email` e `password`; responde com usuário e JWT. |
| `POST /sync` | Sessão `Authorization: Bearer <JWT>`; troca lotes e alterações por cursor. |
| `GET /sync/backup-status` | Sessão JWT; retorna `lastBackupDate`, ou `null` antes da primeira confirmação. |
| `POST /internal/backup` | `Authorization: Bearer <BACKUP_TRIGGER_TOKEN>`; dispara o backup no servidor. |

As sessões expiram em 30 dias. Cadastro e login têm limite de requisições e exigem senha de pelo menos oito caracteres, com no máximo 72 bytes em UTF-8. As rotas legadas de transações e `/docs` pertencem ao modo local original, sem variáveis de sincronização. O histórico legado não é copiado automaticamente para o Turso ao iniciar o servidor.

### Teste local isolado da sincronização

Para testar a API nova sem Turso, use um arquivo SQLite descartável em um diretório privado, fora do repositório. Em PowerShell, abra um terminal temporário e configure:

```powershell
$syncTestDir = Join-Path ([System.IO.Path]::GetTempPath()) ('dt-money-sync-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $syncTestDir | Out-Null
$syncTestFile = Join-Path $syncTestDir 'sync-test.sqlite'
$env:DT_MONEY_SYNC_SQLITE_URL = ([uri]$syncTestFile).AbsoluteUri
$env:APP_SECRET_KEY = node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('hex'))"
Remove-Item Env:TURSO_DATABASE_URL -ErrorAction SilentlyContinue
Remove-Item Env:TURSO_AUTH_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:RENDER -ErrorAction SilentlyContinue
npm start
```

Use somente dados sintéticos. Confira também o `.env`: ele não deve repor as variáveis Turso removidas desse terminal. O arquivo não substitui o banco legado e permanece após encerrar o processo; conserve o caminho para removê-lo quando terminar. Sem `PORT` ou `DT_MONEY_HOST`, o servidor escuta em `127.0.0.1:3001`. Para testes Wi-Fi, configure `DT_MONEY_HOST` conforme as instruções anteriores. HTTP local serve apenas para testes isolados; contas e dados reais usam HTTPS.

`DT_MONEY_SYNC_SQLITE_URL` e `TURSO_DATABASE_URL` são mutuamente exclusivos. O servidor recusa o arquivo local quando `RENDER=true`, evitando depender do disco efêmero da hospedagem.

### Render gratuito para testes

Crie um Web Service com diretório raiz `DT-money-backend`, runtime Node.js 22.23 ou superior, plano gratuito, comando de build `npm ci --include=dev`, comando de início `npm start` e health check `/health`. Configure as variáveis acima no Render. O Render fornece `PORT`; o backend escuta em `0.0.0.0` quando essa variável existe. Não configure `DT_MONEY_HOST` com o IP do seu computador nesse serviço.

O estado sincronizado fica no Turso; os arquivos SQLite de backup existem apenas temporariamente durante a geração. Não é necessário disco persistente. O serviço gratuito pode suspender em repouso, então o primeiro acesso pode demorar. O Worker externo aguarda o cold start e repete falhas transitórias com limite. Use um banco Turso compatível com libSQL para este cliente, sem trocar o mecanismo do banco sem testar a compatibilidade.

### Backup diário e retenção

O [Cloudflare Worker](../cloudflare/backup-worker/README.md) usa o cron `15 6 * * *`: 06h15 UTC, atualmente 03h15 em Brasília. Configure `BACKUP_TRIGGER_URL` com a URL HTTPS completa de `/internal/backup` e o mesmo `BACKUP_TRIGGER_TOKEN` no Worker e no Render. O Worker apenas chama essa rota; o backend lê o Turso em uma transação consistente, materializa e valida o SQLite versionado, criptografa com age e envia ao R2 via HTTPS.

O backup inclui as contas e seus hashes de senha, categorias, transações, histórico de revisões e recibos de sincronização recebidos pelo servidor. **Alterações que ainda estão somente no celular não entram no backup diário.** Texto bruto de notificações e extratos não fazem parte do contrato de sincronização.

No ambiente de teste, o destino é o bucket privado `dt-money-backups-test`, com prefixo `dt-money/test/backups/` e expiração configurada para dez dias nesse prefixo. A chave usa a data de São Paulo: `dt-money/test/backups/AAAA-MM-DD.sqlite.age`. Fora do teste, o prefixo padrão do código é `dt-money/backups/`; configure uma regra correspondente antes de usá-lo. A trava com vencimento no Turso e a gravação condicional no R2 permitem repetir o disparo mantendo uma cópia por dia.

Mantenha o bucket privado, sem URL pública ou domínio público. Use classe Standard e credencial de leitura/gravação de objetos restrita ao bucket. A credencial administrativa usada para configurar a retenção fica separada. A expiração é aplicada pelo R2 e pode ocorrer após o prazo; a última cópia também expira se o serviço ficar dez dias sem novos envios.

Guarde a **identidade privada age de recuperação** com o usuário, separada do servidor e do bucket. O servidor recebe somente `BACKUP_AGE_RECIPIENT`, que é público. Sem a identidade privada correspondente, a restauração do arquivo criptografado não é possível.

Para um disparo manual em PowerShell, com as variáveis já definidas em um terminal privado:

```powershell
Invoke-RestMethod -Method Post -Uri $env:BACKUP_TRIGGER_URL -Headers @{ Authorization = "Bearer $env:BACKUP_TRIGGER_TOKEN" }
```

Uma resposta HTTP 200 informa `uploaded` ou `already_exists`; uma execução concorrente retorna 409 com `in_progress`; falha de geração ou envio retorna 503. Consulte `/sync/backup-status` com a sessão do usuário para ver a data confirmada mais recente. Verifique upload e restauração com dados fictícios antes de conectar dados reais.

### Restauração manual

Baixe o `.sqlite.age` escolhido por acesso autenticado ao R2 e copie a identidade age para um diretório privado da máquina de recuperação. Na pasta `DT-money-backend`, com as dependências instaladas, execute:

```sh
node --import tsx src/backup/restore-cli.ts /private/input.sqlite.age /private/age-identity.txt /private/dt-money-restored.db
```

Substitua os caminhos pelos arquivos locais. O diretório de saída deve existir e o nome de saída deve ser novo: o comando recusa sobrescrever qualquer arquivo existente. A identidade pode conter comentários iniciados por `#` e uma chave por linha. A ferramenta descriptografa, exige a versão 1 e o esquema exato do snapshot, verifica integridade e referências, gera uma nova geração de sincronização e publica o SQLite validado em modo WAL. Ela imprime a revisão recuperada e a nova geração. O arquivo restaurado contém dados em claro e deve permanecer protegido.

Importe esse arquivo para **outro banco Turso**, usando um nome de arquivo único para não confundir o destino:

```sh
turso db import /private/dt-money-restored.db
```

Confira o nome e a URL retornados pelo CLI. Consulte a [documentação oficial de importação](https://docs.turso.tech/cli/db/import); se a sua versão do CLI exigir criação explícita, a alternativa documentada é `turso db create <novo-nome> --from-file /private/dt-money-restored.db`, conforme [db create](https://docs.turso.tech/cli/db/create). Preserve o banco anterior.

Aponte uma instância de teste para o banco importado, confira contas, saldos, histórico e sincronização com dados fictícios. Antes de trocar o ambiente ativo, pause as gravações; só então atualize `TURSO_DATABASE_URL` e `TURSO_AUTH_TOKEN` e reinicie o serviço. A nova geração criada pelo comando invalida os cursores anteriores: o Android reconcilia novamente o histórico completo, mantendo alterações locais pendentes e exibindo conflitos para decisão. A data de confirmação de backup e as travas temporárias não fazem parte do snapshot; o servidor volta a registrá-las nas próximas execuções.
