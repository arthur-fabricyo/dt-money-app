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
