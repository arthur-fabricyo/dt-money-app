# Lançamentos automáticos de notificações bancárias — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Registrar automaticamente no DT Money Android entradas e saídas detectadas em notificações de Nubank e Santander, com categorização local e sincronização sem duplicatas.

**Architecture:** Regras Kotlin extraem os fatos do alerta e o SQLite registra o lançamento e o trabalho pendente de forma atômica. O cliente combina os lançamentos locais com uma visão consistente do backend. Um classificador local sugere categorias depois do registro, e a API aplica sugestões condicionais para preservar edições manuais.

**Tech Stack:** Kotlin, Jetpack Compose, SQLiteOpenHelper, WorkManager, OkHttp, LiteRT-LM, ML Kit GenAI, Fastify, TypeORM e SQLite.

**Spec:** [Especificação aprovada](../specs/2026-11-26-captura-bancaria-android-design.md), commit `221202a`.

## Global Constraints

- Android Kotlin existente, SDK mínimo 27, compilação e alvo 36, Java 17 como alvo do código. Usar o JDK exigido pelo Gradle Wrapper da base.
- Publicações novas dos pacotes habilitados. BRL em centavos, valor positivo, movimentação concluída e direção inequívoca. Créditos e débitos precisam ser explícitos para identificar o meio.
- Identidade e fila isoladas por usuário e URL da API. O texto integral do alerta fica em memória durante a interpretação e fica fora do armazenamento, dos logs e das chamadas remotas.
- **Sem categoria** enquanto a classificação estiver pendente, indisponível ou for inconclusiva. A edição manual tem prioridade. Inferência exclusivamente local.
- Pix entre contas próprias produz dois lados distintos. Preservar filas antigas de revisão manual, criação manual e o contrato usado pelo Expo.
- Amostras sintéticas dão cobertura às regras de software. Formatos de cada banco e desempenho do modelo em segundo plano exigem validação com alertas anonimizados e Android real.

## Review Focus

- Resposta do POST perdida depois da gravação e transação remota fora da página ainda contam uma vez no saldo. Tarefas 1 e 4.
- Dois alertas para lados opostos do mesmo Pix continuam distintos e somam zero. Tarefas 2 e 4.
- Uma sugestão atrasada preserva a categoria escolhida pela pessoa, inclusive se a edição ocorrer em outro cliente. Tarefas 1 e 5.
- Troca de conta ou URL durante processamento mantém os dados e credenciais no escopo de origem. Tarefas 3 e 4.
- Atualizar o banco local preserva estados legados incertos. Tarefa 3.

---

## Arquivos e comandos

- `A` = `dt-money-android/app/src/main/java/com/elfabrica/dtmoney`. `T` = a pasta equivalente em `app/src/test/java`. `I` = a pasta equivalente em `app/src/androidTest/java`.
- `B` = `DT-money-backend/src`. `DB` = `B/infra/database/typeorm/dt-money`. `TX` = `B/infra/web`.
- Android, a partir de `dt-money-android`: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Para instrumentação, com dispositivo ou emulador conectado, `.\gradlew.bat :app:connectedDebugAndroidTest`.
- Backend, a partir de `DT-money-backend`: `npm ci` para preparar dependências e `node --require ts-node/register/transpile-only --test test/bank-import.test.cjs` para os testes. Usar SQLite temporário com migrations, sem escrever no `database.sqlite` do projeto. Usar ts-node para manter a emissão de metadados dos decorators TypeORM.

### Tarefa 0 — Versionar a base Kotlin

**Arquivos:** `dt-money-android/` conforme `git ls-files --others --exclude-standard -- dt-money-android`. Inclui fontes, Gradle Wrapper e configurações compartilhadas.

- [ ] Conferir a lista de arquivos ignorados e não rastreados. Preservar alterações locais. Incluir somente a base compartilhável, sem `local.properties`, saídas de build ou credenciais.
- [ ] Rodar os comandos Android da seção anterior e registrar os resultados de build, testes e lint. Usar o JDK do Wrapper e SDK 36. Se a ferramenta estiver indisponível, registrar o bloqueio exato antes da execução das tarefas Android.
- [ ] Confirmar que o commit da base contém os arquivos necessários a um checkout novo e fazer um commit independente da funcionalidade.

### Tarefa 1 — Contrato de importação e visão consistente do servidor

**Arquivos:** criar `DB/migrations/1795651200000-AddBankImports.ts`, `DT-money-backend/test/bank-import.test.cjs` e o schema/controller de `PATCH /transaction/imports/:importId/category` em `TX`. Alterar as entidades, o repositório TypeORM, a interface de transações, os use cases e os schemas/controllers existentes de criação, atualização e listagem em `B/domain/transaction` e `TX`.

**Interfaces produzidas:**

- `POST /transaction` preserva o corpo manual e aceita o grupo opcional `{ importId: string, occurredAt: string, bank: "nubank" | "santander", method: "pix" | "credit" | "debit" | "unknown", dateSource: "alert" | "posted" }`. `importId` é SHA-256 hexadecimal com 64 caracteres. Datas são ISO 8601 UTC. A resposta de importação contém o ID remoto, o ID de importação e a situação de exclusão, inclusive em repetição.
- `PATCH /transaction/imports/:importId/category` recebe `{ categoryId: number, source: "model" | "manual" }` e retorna a linha vigente. Uma sugestão só se aplica se a categoria vigente for **Sem categoria** e a origem vigente ainda não for manual. O `PUT` legado marca a origem manual quando altera a categoria de um importado. Candidatos de modelo, origem e ID são validados no servidor.
- `GET /transaction` aceita `dateBasis=movement` e `probeImportIds` com até 100 chaves. Quando solicitado, retorna `foundImportIds: string[]` com chaves encontradas para o usuário, inclusive registros excluídos, independentemente do filtro, da página e da ordenação. Lista, totais e chaves são lidos na mesma transação de leitura com o mesmo `EntityManager`.

- [ ] Escrever testes de integração dos handlers com Fastify inject, autenticando dois usuários de teste. Cobrir: dois POST concorrentes e nova tentativa após resposta descartada devolvem o mesmo ID e criam uma linha; mesma chave em outro usuário cria outra linha; repetir após exclusão não recria; movimentação de ontem sincronizada hoje aparece no filtro por movimentação de ontem; o modo legado mantém a base de data atual; totais e chaves incluem a captura fora da primeira página; uma edição manual feita entre sugestão e PATCH mantém a categoria manual.
- [ ] Rodar o comando do backend. Confirmar falha no contrato de importação ausente antes da implementação.
- [ ] Criar colunas opcionais de metadados, origem da categoria e índice único sobre usuário e chave de importação. Popular a categoria `Sem categoria` com um ID estável e disponível, preservando as atuais. Documentar o ID definido no contrato e obtê-lo no cliente a partir do catálogo existente. Validar o grupo de campos de importação no POST e devolver a mesma linha na colisão da chave, sem usar erro genérico como evidência de duplicata.
- [ ] Implementar atualização condicional em SQL para sugestões e autorização por usuário em todos os caminhos. Uma substituição manual explícita, inclusive por **Sem categoria**, bloqueia sugestões tardias. Responder aos reenvios com a categoria vigente.
- [ ] Adaptar a leitura opt-in do Android para aplicar os mesmos filtros e a mesma base de data à listagem e aos totais. Usar `occurredAt` com fallback para `createdAt` no modo `movement`. Comparar datas normalizadas em UTC e testar o caso de filtro somente por data final nesse modo. Manter a semântica das chamadas legadas.
- [ ] Rodar os testes do backend. Esperar zero falhas e confirmar que nenhuma base real foi alterada. Fazer commit da tarefa.

### Tarefa 2 — Extrair movimentações e identificar eventos

**Arquivos:** evoluir `A/BankAlert.kt`; criar `A/BankEventIdentity.kt`; expandir `T/BankAlertTest.kt` e criar `T/BankEventIdentityTest.kt`.

**Interfaces produzidas:** `enum class Bank { NUBANK, SANTANDER }`, `enum class Direction(val typeId: Int)` com `INCOME(1)` e `EXPENSE(2)`, `enum class PaymentMethod { PIX, CREDIT, DEBIT, UNKNOWN }`, `data class BankAlert(val bank: Bank, val title: String, val body: String, val notificationKey: String, val postedAt: Instant, val eventTime: Instant?)`, `data class BankMovement(val importId: String, val bank: Bank, val direction: Direction, val method: PaymentMethod, val cents: Long, val description: String, val occurredAt: Instant, val dateSource: DateSource)` e `fun parseBankMovement(alert: BankAlert): BankMovement?`. `DateSource` tem valores `ALERT` e `POSTED`.

- [ ] Acrescentar fixtures sintéticas rotuladas com padrões em português para as cinco linhas da tabela da especificação e suas negativas. Asserções incluem BRL `1.234,56` → `123456L`, `Direction.INCOME` no Pix recebido, `Direction.EXPENSE` no Pix enviado e meio `UNKNOWN` em compra explicitamente aprovada cujo meio não aparece. Recusa, agendamento, título vago, OTP, moeda divergente, dois valores sem total inequívoco e overflow devem produzir `null`. Cobrir alertas com e sem data explícita confiável.
- [ ] Testar que repetir o mesmo evento produz a mesma chave, compras iguais com eventos distintos produzem chaves distintas e o mesmo identificador bancário em lados opostos de um Pix produz duas chaves. Quando houver referência explícita, incluir banco e direção na identidade. Na alternativa, usar banco, direção, identidade e instante estáveis do Android e os fatos normalizados necessários para distinguir eventos.
- [ ] Rodar `.\gradlew.bat :app:testDebugUnitTest --tests '*BankAlertTest' --tests '*BankEventIdentityTest'`. Confirmar falha pelas interfaces ou regras ausentes.
- [ ] Implementar os parsers por formato, a resolução da data e a geração da chave. Manter as fixtures sintéticas identificadas e ativar em produção somente formatos e identificadores de pacote confirmados na Tarefa 6. Sanitizar a descrição e excluir dados dispensáveis do alerta.
- [ ] Repetir os testes filtrados e fazer commit.

### Tarefa 3 — Captura e lançamento local durável

**Arquivos:** `A/BankNotificationService.kt`, `A/AppSettings.kt`, `A/ImportStore.kt`, `app/src/main/AndroidManifest.xml` se necessário. Criar `I/ImportStoreTest.kt` e `T/BankCaptureConfigurationTest.kt`.

**Interfaces:** consome `parseBankMovement`. Produz `data class CaptureConfig(val scope: String, val enabledBanks: Set<Bank>, val enabledSince: Map<Bank, Instant>)`, `ImportStore.record(scope: String, movement: BankMovement): Boolean` e `ImportStore.localImports(scope: String): List<LocalImport>`. `LocalImport` contém `movement: BankMovement`, `remoteId: Long?`, `categoryId: Int?`, `categorySource: CategorySource` e `syncState: SyncState`. Origens `UNSET, MODEL, MANUAL`. Estados `PENDING_CREATE, PENDING_CATEGORY, SYNCED`. O armazenamento identifica também se a tentativa local de inferência foi concluída.

- [ ] Testar em SQLite real que upgrade v1 preserva capturas pendentes e incertas sem promovê-las a lançamentos, que repetir o mesmo insert gera um lançamento e que lançamento e trabalho entram juntos. Testar que dois escopos podem receber a mesma chave, que logout ou mudança de configuração invalida um callback pendente e que alertas anteriores à ativação ficam de fora.
- [ ] Executar os testes unitários e instrumentados da tarefa. Confirmar falha na captura generalizada ou na migração ausente.
- [ ] Adicionar uma tabela de importados com chave primária `(scope, importId)` e preservar a tabela v1 de revisão. Tratar a configuração antiga de pacote como configuração legada até a pessoa habilitar o modo automático. Registrar trabalho e lançamento atomicamente. Tornar a leitura de configuração e o registro serializáveis em relação a logout e troca de escopo no cliente.
- [ ] Restringir o listener aos pacotes confirmados e selecionados, a publicações posteriores à ativação e a eventos individuais. Encaminhar interpretação e escrita para o executor fora da thread do callback.
- [ ] Repetir testes unitários e instrumentados. Fazer commit da tarefa após a verificação.

### Tarefa 4 — Sincronização, histórico, saldo e configuração

**Arquivos:** `A/MoneyApi.kt`, `A/MoneyViewModel.kt`, `A/MainActivity.kt`, `A/ImportStore.kt`, `A/AppSettings.kt`, `dt-money-android/app/build.gradle.kts`. Criar `A/BankImportWorker.kt`, `A/ImportReconciliation.kt`, `T/ImportReconciliationTest.kt` e `I/BankImportWorkerTest.kt`.

**Interfaces:** consome `LocalImport` e o contrato da Tarefa 1. Produz `suspend fun MoneyApi.createImport(entry: LocalImport, uncategorizedId: Int): ImportReceipt`, `suspend fun MoneyApi.updateImportCategory(importId: String, categoryId: Int, source: CategorySource): ImportReceipt`, `suspend fun MoneyApi.transactions(page: Int, filters: Filters, probeImportIds: Set<String> = emptySet()): TransactionPage` e `fun reconcileImports(remote: TransactionPage?, local: List<LocalImport>, filters: Filters): ReconciledDashboard`. `ImportReceipt` contém `id: Long, importId: String, categoryId: Int, deleted: Boolean`. `TransactionPage` inclui `foundImportIds`, as chaves sondadas no pedido e os metadados importados das linhas retornadas.

- [ ] Testar o saldo e histórico com entrada local de 1000 centavos e saída de 400, passando de 600 centavos offline para 600 durante e após o envio. A resposta pode se perder e o lançamento remoto pode estar fora da página ou filtrado por categoria. Testar cache antigo, nova captura durante a consulta, resposta obsoleta de outra sessão e fila com mais de 100 importados. Testar um Pix próprio de 400 centavos por lado com os dois bancos habilitados, resultando em 400 centavos de entradas, 400 de saídas e saldo líquido zero.
- [ ] Testar o worker com falha de transporte que persiste o POST, repetição com o mesmo payload, troca de usuário/URL antes do envio, logout durante resposta e sessão expirada. Conferir as credenciais selecionadas e o estado da fila. Executar testes unitários e instrumentados e confirmar falha antes da implementação.
- [ ] Agendar trabalho durável por escopo com WorkManager. Persistir recibos e mudanças de estado no mesmo escopo. Revalidar identidade antes da captura de credenciais de cada chamada, cancelar trabalho ao sair da conta e descartar efeitos de respostas na sessão visível quando o escopo mudar. Aplicar backoff em falhas transitórias, manter pendências após 401 e retomar no próximo login do mesmo escopo.
- [ ] Conciliar usando a mesma resposta para linhas, totais e chaves sondadas. Somar ao total remoto apenas os importados locais ausentes dessa visão e que passem pelo mesmo filtro. Se a chave existe na visão remota, usar a representação do servidor. Serializar os POSTs e a leitura de conciliação no mesmo escopo, da coleta das chaves até a leitura do servidor. Limitar a 100 os importados em envio ou com resultado incerto entre leituras bem-sucedidas. Antes de liberar o lote seguinte, o worker verifica o lote anterior com uma leitura sem filtros. Preservar o vínculo da importação com o ID remoto e o estado observado nas visões já obtidas. Chaves ainda não enviadas continuam no saldo local. Manter a última visão por escopo e filtro para uso offline, com indicação de total parcial quando a visão não puder confirmar todas as chaves relevantes.
- [ ] Integrar no histórico e saldo do `MoneyViewModel` e nas telas Compose. Usar data da movimentação no filtro e na exibição de importados, com fallback para a data de criação nos demais. Usar as mesmas regras de filtro e ordenação ao combinar itens locais e remotos. Adicionar controles independentes para Nubank e Santander, estados de permissão e de sincronização e edição de categoria em lançamentos capturados. Manter a revisão legada acessível. Uma edição manual em importado local deve persistir antes de atualizar o backend.
- [ ] Rodar testes unitários e instrumentados, build e lint. Fazer commit.

### Tarefa 5 — Categoria por IA local

**Arquivos:** criar `A/BankCategoryClassifier.kt`, `A/FunctionGemmaClassifier.kt`, `A/GeminiNanoClassifier.kt` e `T/BankCategoryClassifierTest.kt`. Integrar em `A/BankImportWorker.kt`, `A/MoneyViewModel.kt`, `A/ImportStore.kt` e `dt-money-android/app/build.gradle.kts`.

**Interfaces:** `data class CategoryContext(val description: String, val direction: Direction, val method: PaymentMethod)`, `interface BankCategoryClassifier { suspend fun suggest(context: CategoryContext, categories: List<Category>): CategorySuggestion }`, resultado fechado `CategorySuggestion.Match(categoryId: Int), Abstain, Unavailable`. `ImportStore.applyCategorySuggestion(scope: String, importId: String, categoryId: Int): Boolean` aplica a sugestão e enfileira envio atomicamente quando o estado ainda permite.

- [ ] Testar que um ID válido vira categoria, um ID fora do catálogo, resultado ambíguo ou saída malformada mantém **Sem categoria**, indisponibilidade preserva o lançamento e uma edição manual antes do retorno tem prioridade. Testar que a camada de classificação recebe somente `CategoryContext` e categorias. Rodar `.\gradlew.bat :app:testDebugUnitTest --tests '*BankCategoryClassifierTest'` e confirmar falha.
- [ ] Integrar FunctionGemma 270M via LiteRT-LM com uma única ação declarada de seleção de categoria ou abstinência. Validar catálogo e saída em Kotlin. Encadear trabalho de inferência após persistência da captura e independente da conexão da API. Controlar concorrência e retentativas para liberar memória e manter a captura responsiva. O registro usa **Sem categoria** até resultado aceito. Definir distribuição e versão do modelo após validar tamanho e desempenho. Manter o binário fora do Git.
- [ ] Integrar Gemini Nano via ML Kit como alternativa em dispositivos compatíveis quando o aplicativo já estiver em primeiro plano. Se um runtime não estiver disponível, manter **Sem categoria**. Validar a disponibilidade antes de inferir e reutilizar o mesmo contrato de saída. Aplicar recomendações apenas enquanto a edição local e o servidor ainda permitirem.
- [ ] Repetir testes unitários, build e lint. Fazer commit da integração e condicionar sua ativação em produção à verificação seguinte.

### Tarefa 6 — Validar em aparelho e habilitar os formatos confirmados

**Arquivos:** `dt-money-android/README.md`, fixtures anonimizadas da Tarefa 2 e arquivo de registro de resultados `docs/superpowers/specs/2026-11-26-validacao-captura-android.md`. Atualizar as regras, a tabela de pacotes e a configuração de ativação conforme os resultados.

- [ ] Obter exemplos anonimizados dos dois bancos e identificar os pacotes e os formatos observados no aparelho de referência. Separar exemplos para desenvolvimento e avaliação. Avaliar aceitação de categoria e abstinência em exemplos ambíguos. Medir precisão das sugestões aceitas, cobertura, latência, uso de memória e tamanho do modelo. Registrar aparelho, versão do Android, runtime e versão do modelo.
- [ ] Exercitar Pix recebido e enviado, crédito, débito e compra de meio desconhecido quando esse alerta existir, com o app fora da tela. Testar reinício de processo, modo avião, retentativa após POST gravado, revogação da permissão, edição manual concorrente, alerta repetido, logout e troca de conta. Conferir totais de entradas, saídas e saldo antes e depois do sincronismo.
- [ ] Habilitar formatos e categorização que passaram pela validação, com critérios registrados antes do teste final. Anotar explicitamente combinações de banco e meio sem exemplos reais e aparelhos não verificados. Se a inferência em segundo plano não atingir os critérios, manter captura e lançamento automáticos com **Sem categoria** e registrar o motivo observado.
- [ ] Rodar os comandos do backend e Android, incluindo instrumentação quando houver aparelho ou emulador. Documentar ativação, funcionamento offline e limites verificados no README. Fazer commit e verificar o fluxo final no checkout resultante.
