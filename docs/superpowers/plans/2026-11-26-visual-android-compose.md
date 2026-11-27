# Visual do DT Money Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproduzir o visual do React Native no Android Kotlin e entregar o APK debug testado no aparelho.

**Architecture:** Tema Compose com os recursos existentes, telas de autenticação e componentes de painel independentes, e formulários em painéis inferiores. O ViewModel e a persistência continuam fornecendo os dados e as ações.

**Tech Stack:** Kotlin, Jetpack Compose, Android Studio SDK, Gradle, ADB.

**Spec:** `docs/superpowers/specs/2026-11-26-visual-android-compose-design.md`

## Restrições

- Usar o logotipo, paleta, tipografia e hierarquia do cliente React Native no repositório.
- Manter URL configurável, operações offline, captura e sincronização existentes.
- Controles associados a um gesto precisam de uma alternativa acessível.

## Foco de revisão

- Entrar e cadastrar com teclado em tela, texto ampliado e largura reduzida.
- Deslizar um cartão permite acessar a ação sem disparar edição ou exclusão indevida.
- Saldo parcial, sincronização pendente e transações importadas continuam visíveis.
- Filtros e edição mantêm opções de limpar, confirmar, cancelar e escolher categoria.
- Ações de Capturas e do modelo continuam acessíveis com o Android sem permissão concedida.

### Tarefa 1 — Tema e telas

- [x] Capturar o comportamento anterior e definir critérios verificáveis para o cabeçalho, cartões e componentes compartilhados.
- [x] Implementar `DtMoneyTheme`, `DtLogo`, `DtButton`, `DtField`, `DtCard` e `DtSheet` em `DtMoneyTheme.kt`, usando os recursos do Expo e tokens do repositório.
- [x] Implementar `SignIn` em `AuthScreen.kt` e componentes de cabeçalho, resumo, pesquisa e cartão em `TransactionComponents.kt`, conectando-os em `MainActivity.kt`.
- [x] Adaptar os formulários, filtros, seletores e Capturas em `MainActivity.kt` e `BankReviewDialog.kt`.

### Tarefa 2 — Verificar e entregar

- [x] Executar `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`.
- [ ] Instalar no Moto G84 pareado, executar testes instrumentados e conferir login e telas acessíveis a partir do aparelho. Registrar limitações de acesso a dados ou do ambiente, se houver.
- [x] Copiar o APK assinado para `Downloads/apks` do usuário Windows, verificar assinatura e igualdade do SHA‑256 com o build.

O build, os 27 testes unitários e o lint passaram. O Moto G84 desconectou do ADB antes da instalação do APK redesenhado. A validação em aparelho aguarda a reconexão.
