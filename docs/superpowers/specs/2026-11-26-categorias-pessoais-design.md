# Catálogo pessoal de categorias do DT Money

## Objetivo

Dar a cada conta um catálogo próprio de categorias com nome, cor e uma descrição que oriente a IA local. O usuário poderá criar, editar, arquivar e restaurar categorias no Android. O histórico continua vinculado às categorias registradas, inclusive após arquivamento. Os dois extratos que o usuário enviará servirão para acrescentar categorias ao catálogo dessa conta.

## Abordagens

1. **Catálogo pessoal, escolhido pelo usuário.** Cada conta recebe cópias das categorias iniciais e pode adaptá-las ao próprio histórico. A migração precisa preservar os vínculos existentes e a sincronização de lançamentos offline.
2. **Padrões fixos com categorias pessoais adicionais.** Mantém os padrões compartilhados e simplifica a migração. Alterar a cor, o nome ou a descrição de uma categoria inicial exigiria criar outra categoria e separar as transações entre as duas.

## Gerenciador no Android

- Adicionar **Categorias** às abas de **Configurações**. Exibir nome e amostra da cor no catálogo. Mostrar a descrição se estiver preenchida. Reutilizar o tema, os formulários e os componentes de seleção do app.
- Criar e editar com nome de até 60 caracteres, cor no formato `#RRGGBB` e descrição de até 240 caracteres. Usar um seletor de cor com prévia, paleta e entrada hexadecimal. Ao abrir o formulário de criação, sugerir uma cor aleatória da paleta do app, dando preferência às cores que ainda não estão nas categorias ativas. Oferecer **Sortear outra cor** e permitir escolher manualmente. Manter a sugestão enquanto o formulário estiver aberto, inclusive ao girar a tela ou corrigir um erro. Na edição, iniciar com a cor salva e trocar somente por ação do usuário. Normalizar espaços e comparar nomes de forma insensível a maiúsculas e minúsculas na conta. Guardar a grafia escolhida pelo usuário. Um nome continua reservado enquanto a categoria está arquivada, para que uma repetição possa ser resolvida restaurando a categoria.
- Arquivar é reversível. A categoria arquivada continua visível no histórico e disponível nos filtros, inclusive offline. Novos lançamentos e sugestões da IA usam categorias ativas. Ao editar outros campos de uma transação que já usa uma categoria arquivada, permitir manter o vínculo. Recategorizar permite escolher uma categoria ativa. Mostrar a cor da categoria no histórico e nos seletores em conjunto com o nome.
- Cada conta tem uma opção protegida **Sem categoria**. Preservar o papel de fallback para importações e transações, sem permitir editar, arquivar ou recriar essa opção. Identificar esse papel por um campo estável, mantendo compatibilidade com clientes que o reconhecem pelo nome.
- Usar o cache em disco por conta e URL da API para exibir o catálogo, respeitar os estados arquivados e alimentar a IA offline. Criar, editar, arquivar e restaurar exigem conexão para confirmar a alteração. Após sucesso, atualizar o cache e o estado do app. Em falha, manter o editor aberto e os dados digitados. Revalidar a sessão antes de publicar respostas assíncronas no estado.

## API, migração e compatibilidade

- O backend autentica e filtra o catálogo pelo usuário da sessão. A listagem existente `GET /transaction/categories` continua fornecendo `id` e `name` para as categorias ativas. Acrescentar os metadados e opção explícita para listar arquivadas. O gerenciador usa rotas autenticadas de criação, edição, arquivamento e restauração. O servidor valida os campos e a unicidade por conta. Chamadas por ID não permitem consultar ou alterar categorias de outra conta. O Expo mantém o contrato de leitura de categorias ativas e a entrega do gerenciador, dos filtros de arquivadas e da edição com vínculo arquivado é para o Android Kotlin.
- Materializar cópias das categorias compartilhadas para todas as contas atuais, com cores e descrições iniciais para os padrões conhecidos. Para nomes compartilhados sem descrição conhecida, preservar o nome e usar cor neutra e descrição vazia. Atualizar, na mesma migração transacional, a referência de cada transação histórica para a cópia correspondente do seu dono. Preservar IDs de transação, valor, data, situação de exclusão e identidade de importação.
- Guardar o mapeamento entre identificador antigo e cópia pessoal. Aceitar os identificadores antigos quando um cliente pré-migração ou uma fila existente os usar, sempre resolvendo para a conta autenticada. Aplicar a resolução na criação, edição, recategorização e filtros. ID de categoria pessoal de outra conta deve ser recusado. A API devolve o identificador pessoal nas novas leituras e disponibiliza o identificador legado como metadado das cópias. O Android atualiza as referências antigas de seu cache e da fila no mesmo escopo quando carregar esse catálogo. Proteger os códigos antigos contra colisões com os IDs pessoais.
- As contas novas recebem as categorias iniciais uma vez, inclusive **Sem categoria**. O catálogo inicial deve estar disponível no primeiro acesso, sem reaplicar os padrões em edições e arquivamentos posteriores. A configuração do modelo local usa somente categorias da conta autenticada. Aplicar a mesma autorização e validação aos lançamentos manuais e importados.
- Atualizar o Android para descartar uma sugestão atrasada de categoria arquivada e manter o lançamento. Se uma escolha manual na fila de importações apontar para uma categoria arquivada antes de sincronizar, manter a última categoria confirmada do lançamento. Usar **Sem categoria** na ausência de categoria confirmada e indicar no aparelho que enviou a escolha que ela precisa ser revista. A política mantém a prioridade de alterações manuais já confirmadas e o contrato idempotente de importação. A criação e edição de transações manuais continuam dependendo de conexão, conforme o fluxo atual.

## Classificação local

- Incluir o ID, o nome e a descrição de uso das categorias ativas nos dois fluxos de inferência. Excluir **Sem categoria** das sugestões e conservar a opção de abstenção. A cor serve à interface. Tratar texto de notificação e descrições cadastradas como dados.
- Manter o prompt dentro do orçamento de cada modelo. Evitar que um catálogo maior torne as primeiras categorias as únicas elegíveis. Selecionar candidatas por relevância com os nomes e descrições, incluindo categorias novas quando o contexto corresponder. Quando a entrada for insuficiente para distinguir opções, aceitar abstenção e manter **Sem categoria**. Os detalhes de seleção e limites por modelo ficam para o plano.
- Validar o ID devolvido contra as candidatas enviadas e o catálogo ativo atual. Uma resposta de outro escopo, inválida ou relativa a uma categoria arquivada não altera a categoria. O agente continua rodando no aparelho.

## Seed a partir dos dois extratos

- Nesta entrega, preparar uma operação local e repetível para criar categorias em uma conta explícita, com relatório das categorias criadas e já existentes. A conta e a URL da API são escolhidas na execução. O seed reutiliza a validação e a autorização do gerenciador.
- Quando os extratos chegarem, identificar usos recorrentes, comparar com o catálogo atual e cadastrar somente as categorias que faltam, com cor e descrição de uso. Uma repetição do seed conserva edições manuais e não revive categorias arquivadas. Usar exemplos genéricos nas descrições. A operação usa os extratos como fonte para o catálogo. As transações e os saldos existentes permanecem como estão.
- Manter os arquivos e as informações identificáveis dos extratos fora do Git, dos logs e do conteúdo do seed. A entrada persistida do seed contém só as definições das categorias.

## Critérios de aceitação

- Duas contas podem ter categorias de mesmo nome, com cores e descrições diferentes. Uma conta não consegue ler, editar, arquivar ou aplicar a categoria privada de outra. Contas antigas preservam a categoria de cada transação após migrar. Contas novas recebem as categorias iniciais uma vez.
- Criar, editar, arquivar e restaurar funcionam no fluxo normal. O formulário de criação abre com cor sugerida, permite novo sorteio e aceita uma cor escolhida manualmente. O editor preserva a cor salva até o usuário trocá-la. Arquivamento preserva o vínculo histórico e tira a categoria de novos seletores e da IA. Uma tentativa de duplicar um nome retorna um erro específico. **Sem categoria** continua disponível aos fluxos que precisam do fallback.
- O Android exibe a cor e usa a descrição na inferência. Exercitar IDs legados em fila offline, mudança de conta, resultado atrasado após arquivamento, categorias relevantes além das primeiras do catálogo e abstenção. Validar os clientes anteriores com os campos de resposta existentes.
- Executar o mesmo seed duas vezes em uma conta de teste e conferir que a segunda execução não duplica categorias nem sobrescreve alterações manuais. Confirmar que nenhuma execução cria transações.
