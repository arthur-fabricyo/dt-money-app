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
git clone https://github.com/brenooliveiranascimento/DT-money-backend.git
cd dtmoney-backend
```

### 2️⃣ Instalar as dependências

Utilize o gerenciador de pacotes **yarn** ou **npm** para instalar todas as dependências do projeto:

```sh
yarn
ou
npm i
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
yarn migration:run
ou
npm run migration:run
```

### 5️⃣ Iniciar o servidor

Agora, basta rodar o servidor com:

```sh
yarn dev
ou
npm run dev
```

O backend estará rodando em `http://localhost:3001`.
Para acessar a documentação `http://localhost:3001/docs`
