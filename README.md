# 💰 DT Money App: Gestão Financeira Pessoal com Performance e Escalabilidade

## 🎯 O Problema que este Projeto Resolve

Gerenciar finanças pessoais pode ser um desafio, especialmente quando se busca uma visão clara e organizada de receitas e despesas. Muitas aplicações existentes falham em oferecer uma experiência de usuário fluida, com carregamento rápido e navegação intuitiva, ou carecem de funcionalidades essenciais como filtragem avançada e categorização eficiente. Além disso, a performance em dispositivos móveis é crucial para uma boa experiência.

Este projeto foi desenvolvido para resolver a necessidade de uma ferramenta de gestão financeira pessoal que seja:
1.  **Performática e Responsiva:** Oferecendo uma experiência de usuário fluida e sem travamentos, mesmo com um grande volume de transações.
2.  **Intuitiva e Completa:** Permitindo o registro fácil de transações, categorização, filtragem e visualização de saldos.
3.  **Escalável e Manutenível:** Construída com uma arquitetura que facilita a adição de novas funcionalidades e a manutenção do código.
4.  **Otimizada para Mobile:** Com foco em uma experiência nativa e eficiente em dispositivos móveis.

---

## Visão Geral

O DT Money App é uma aplicação móvel de gestão financeira pessoal para registrar, categorizar e visualizar receitas e despesas. O cliente Android nativo usa **Kotlin e Jetpack Compose** e utiliza o backend Fastify para sincronização opcional.

### Android nativo

O Android mantém transações e categorias em SQLite no celular. O botão **Usar neste aparelho** abre um perfil local. O painel, os lançamentos manuais, as capturas e a edição de categorias funcionam offline. Em **Configurações → Categorias**, cada categoria tem nome, cor e descrição para orientar a IA local. O seletor oferece paleta, cor aleatória e valor hexadecimal. Categorias arquivadas conservam seus vínculos históricos.

Em **Configurações → Sincronização e backup**, conectar uma conta ativa a sincronização opcional entre instalações Kotlin. A API Fastify no Render autentica a conta e grava no Turso. As alterações ficam em uma fila no SQLite até a confirmação do servidor. A tela permite acompanhar pendências e resolver conflitos. Desconectar conserva o perfil no aparelho.

O servidor gera um backup criptografado por dia dos dados que recebeu. Um Cloudflare Worker dispara a tarefa às **03h15 de Brasília**, usando `15 6 * * *` em UTC. O bucket privado R2 usa expiração de **dez dias** no prefixo dos backups. Dados ainda pendentes no aparelho entram no próximo backup após a sincronização. O [guia do backend](DT-money-backend/README.md) descreve as variáveis do Render, o ambiente Turso de teste e a restauração.

O catálogo aceita importação JSON com prévia e reconhecimento de categorias já existentes. Os dois extratos dos meses anteriores serão usados para preparar o seed quando forem disponibilizados.

O APK de desenvolvimento registra Pix e compras aprovadas a partir de notificações do Nubank e do Santander. Para formatos novos, a IA local sugere os dados da transação e o app valida a movimentação antes de lançar. Veja como gerar o APK, configurar o login e testar no celular por Wi‑Fi no [guia do Android](dt-money-android/README.md).

## Componentes do projeto

- [Android nativo](dt-money-android/README.md): aplicativo Kotlin com Jetpack Compose e SQLite.
- [Backend](DT-money-backend/README.md): API Fastify para autenticação, sincronização e backups.
- [Worker de backup](cloudflare/backup-worker/README.md): agendamento dos backups no Cloudflare.
