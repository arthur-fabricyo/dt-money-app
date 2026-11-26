# Interpretação local de notificações bancárias Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Interpretar formatos novos do Nubank e Santander e confirmar lançamentos com evidência textual ou revisão manual.

**Architecture:** O caminho Kotlin existente tem prioridade. Um candidato estruturado sobrevive à inferência e é promovido pelo mesmo armazenamento que registra formatos conhecidos.

**Tech Stack:** Kotlin, Compose, SQLite, LiteRT-LM, ML Kit, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-26-interpretacao-local-notificacoes-design.md`

## Restrições

- Inferência local em builds de teste para pacotes habilitados, com FunctionGemma 270M antes de Gemini Nano em primeiro plano.
- Nenhum texto da notificação fica em disco ou sai do aparelho. O backend recebe o lançamento estruturado existente.
- Lançamento automático exige evidência para BRL, conclusão e direção. A revisão fica fora do saldo.

## Foco de revisão

- Valor inventado, negado, de outra moeda ou concorrendo com outro valor → testes da tarefa 1.
- Categoria desconhecida em movimentação válida → tarefa 1.
- Encerramento durante a inferência e atualização de uma notificação → tarefa 2.
- Conta desconectada e resultado do modelo atrasado → tarefa 3.
- Confirmação manual offline sem catálogo → tarefa 3.

### Tarefa 1 — Validar proposta local

- [ ] Criar `BankAlertInterpretationTest.kt` em `dt-money-android/app/src/test/java/com/elfabrica/dtmoney`. Exercitar `prepareBankAlert(alert)` e `validateBankProposal(input, proposal, categories)` com Pix nos dois sentidos, compras aprovadas, categorias fora do catálogo, valores contraditórios e mensagens fora de escopo.
- [ ] Executar `:app:testDebugUnitTest --tests '*BankAlertInterpretationTest'` e observar referências ainda ausentes.
- [ ] Implementar a preparação do candidato e a validação sem I/O em `BankAlertInterpretation.kt`. Reusar valores, datas e construção de movimentos de `BankMovement.kt`.
- [ ] Executar os testes unitários e verificar o resultado.

### Tarefa 2 — Persistir revisão e decisão única

- [ ] Adicionar cenários a `ImportStoreTest.kt` de revisão após reabertura, descarte, confirmação de entrada e mudança para formato reconhecido com a mesma identidade.
- [ ] Implementar migração v3 e operações de revisão em `ImportStore.kt`. `recordCaptured` recebe identidade do alerta e `BankMovement`. A promoção grava o lançamento e encerra a revisão na mesma transação SQLite.
- [ ] Executar `:app:assembleDebugAndroidTest`. Com aparelho, executar `:app:connectedDebugAndroidTest` e conferir os casos de migração e concorrência.

### Tarefa 3 — Conectar Android, modelos e interface

- [ ] Integrar os dois modelos em `BankAlertModels.kt` e `FunctionGemmaClassifier.kt`. Usar um contrato de proposta, orçar tempo de análise e confirmar o escopo antes de escrever.
- [ ] Alterar `BankNotificationService.kt` para manter o parser existente, persistir o candidato antes de inferir e enfileirar a sincronização após o lançamento.
- [ ] Expor revisão, descarte e confirmação offline em `MoneyViewModel.kt`, `MainActivity.kt` e `BankReviewDialog.kt`, inclusive sem categorias carregadas.
- [ ] Verificar `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`. Testar os modelos em aparelho compatível quando disponível e registrar a limitação se o ambiente não tiver aparelho.
- [ ] Versionar as mudanças verificadas e entregar o APK de teste e o PR.
