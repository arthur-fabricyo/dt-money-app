# Lançamentos automáticos de notificações bancárias no DT Money Android

## Objetivo

Reduzir o lançamento manual de movimentações usando o app Kotlin com Jetpack Compose em `dt-money-android`. Depois que o usuário habilitar a captura para o Nubank ou o Santander e conceder acesso às notificações no Android, uma movimentação concluída e reconhecida entra automaticamente no histórico e no saldo, mesmo com o app fora de primeiro plano. A classificação por categoria roda no aparelho.

O cliente nativo existente já usa o backend Fastify para autenticação, categorias, saldo e transações. Ele escuta um pacote configurado, reconhece um formato de compra aprovada e mantém uma fila de revisão em SQLite. O formulário e a API atuais exigem categoria para criar uma transação.

## Escopo

| Alerta reconhecido | Lançamento | Meio de pagamento |
| --- | --- | --- |
| Pix recebido | Entrada | Pix |
| Pix enviado e concluído | Saída | Pix |
| Compra aprovada no crédito | Saída | Crédito |
| Compra aprovada no débito | Saída | Débito |
| Compra aprovada cujo alerta não identifica o meio | Saída | Desconhecido |

Valem os formatos confirmados para Nubank e Santander no Brasil. O lançamento exige valor positivo em BRL e direção inequívoca. Quando a descrição comercial estiver ausente, use uma descrição genérica com o tipo de movimentação e o banco. Alertas de autenticação, publicidade, compras recusadas, agendamentos, cobranças, faturas e estornos ficam fora do lançamento automático inicial. Alertas ambíguos sobre valor, direção ou conclusão também ficam fora do saldo. Histórico anterior à ativação, SMS, e-mail, Open Finance, clientes iOS e substituição do backend ficam fora desta entrega.

Por padrão, um Pix entre contas próprias dos dois bancos gera uma saída e uma entrada quando os dois alertas são recebidos. Cada lançamento afeta seu respectivo total e o efeito líquido no saldo é zero. O identificador de um Pix compartilhado pelos dois bancos só deduplica alertas do mesmo lado da movimentação.

## Abordagens

1. **Escolhida pelo usuário: registro e categorização em segundo plano.** Registro imediato no SQLite, classificação assíncrona com FunctionGemma 270M e envio idempotente ao backend. Atende à automação com o app fechado. Exige medir tempo, memória e qualidade no aparelho e tratar tentativas de sincronização.
2. **Registro em segundo plano, categorização em primeiro plano.** O lançamento fica pendente de categoria até o usuário abrir o app. O Gemini Nano pode atender aparelhos compatíveis nesse fluxo. O ML Kit bloqueia inferência em segundo plano, inclusive em serviço em primeiro plano. [Documentação do ML Kit](https://developers.google.com/ml-kit/genai/#background_usage)

## Captura, registro e categorização

1. Reaproveitar o serviço de notificações do Android. Oferecer seleção independente dos dois bancos e ativação explícita no escopo da sessão e da URL da API. Processar apenas notificações novas dos pacotes habilitados. Ignorar resumos de grupo.
2. Interpretar título e corpo em código Kotlin com regras por banco, verificadas com exemplos anonimizados ou observados em aparelhos. Extrair valor em centavos, direção, meio, descrição e instituição. Usar data explícita quando disponível e confiável. Caso contrário, usar a hora de publicação e registrar sua origem. Classificar compras de meio desconhecido apenas quando o alerta indicar de forma inequívoca uma compra aprovada. [API de notificações do Android](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
3. Em uma transação SQLite, gravar o evento válido, seu identificador de importação, o lançamento local e o trabalho pendente. Mostrar o lançamento no histórico e incluí-lo no saldo local ainda que a rede ou o modelo estejam indisponíveis. Um evento repetido deve referenciar o mesmo lançamento. Usar referência bancária quando publicada ou a identidade do evento do Android com discriminadores estáveis. Compras distintas de mesmo valor, data e texto continuam distintas. Alertas sem identificador compartilhado entre fontes diferentes ficam fora da deduplicação entre fontes.
4. Executar a classificação assíncrona após o registro. Avaliar FunctionGemma 270M via LiteRT-LM com exemplos em português dos dois bancos e com o catálogo do DT Money. A entrada do modelo contém somente o contexto necessário, como descrição sanitizada, direção e meio. A saída aponta para uma categoria do catálogo ou se abstém. O código valida a resposta antes de persistir. O modelo recebe apenas a função de sugerir categoria. [Visão geral do FunctionGemma](https://ai.google.dev/gemma/docs/functiongemma) · [Implantação Android com LiteRT-LM](https://ai.google.dev/gemma/docs/mobile-actions)
5. Aceitar apenas respostas válidas e aprovadas pelos critérios do teste de classificação. Salvar como **Sem categoria** se o contexto for insuficiente ou o modelo estiver indisponível. Nos aparelhos compatíveis, o Gemini Nano pode processar pendências quando o usuário estiver no app. Uma edição manual da categoria tem prioridade sobre resultados assíncronos, inclusive após reabertura ou nova tentativa.

## Histórico, saldo e sincronização

- Separar estado de sincronização do estado da categoria. O lançamento pode ser sincronizado como **Sem categoria** e receber a sugestão válida depois. Representar **Sem categoria** por um valor aceito pelo backend sem quebrar clientes que exigem um ID de categoria. A modelagem exata fica para o planejamento.
- Estender o contrato atual de modo retrocompatível para aceitar identificador de importação, data da movimentação e origem bancária opcional. Manter a data de criação no servidor. No histórico e nos filtros do cliente nativo, usar a data da movimentação quando houver e a data de criação para dados antigos.
- A API aplica unicidade do identificador de importação por usuário e devolve a transação já criada se receber novamente o mesmo identificador. Persistir a fila até confirmação e vincular a resposta ao ID local. Em caso de timeout, reinício ou nova entrega, retentar com o mesmo identificador. Estender a sincronização para propagar alterações posteriores de categoria de forma que uma sugestão atrasada respeite edições manuais.
- Conciliar lançamentos locais e remotos pela identidade de importação ou ID do servidor. Enquanto o lançamento ainda não estiver representado na visão remota, ele entra no saldo e no histórico do Android como pendente. A confirmação e a chegada da cópia remota devem substituir essa representação sem duplicar o saldo. Uma resposta de listagem paginada, sozinha, não prova que um agregado remoto já inclui ou exclui determinado lançamento.
- Isolar dados e trabalhos por usuário e URL da API. Pausar o trabalho da sessão ao sair da conta, retomar apenas no mesmo escopo e preservar lançamentos ainda pendentes. Retomar a fila após reinício de processo e reconexão quando o Android permitir.
- Migrar a fila legada preservando seu estado. Capturas antigas mantêm a revisão manual, inclusive as que podem ter sido enviadas com resultado incerto. A automação começa para novas capturas após ativação.

## Privacidade e operação

Inferência exclusivamente no aparelho. A API recebe os campos estruturados do lançamento necessários ao histórico e à sincronização. O corpo completo da notificação não deve ser persistido nem enviado em telemetria. A revogação da permissão do Android ou a desativação de um banco encerra novas capturas da origem. A interface mostra se a captura está habilitada, se um lançamento aguarda sincronização e se está em **Sem categoria**, com acesso à edição existente.

## Validação e decisões de implementação

- Cobrir, com amostras rotuladas de cada banco, Pix enviado e recebido, cartão de crédito e débito, compra sem meio explícito e alertas fora do escopo. Usar dados anonimizados e verificar os formatos em aparelho antes de declarar cobertura de cada combinação. A disponibilidade de amostras reais dos dois bancos e de um Android de referência ainda precisa ser confirmada. Casos sintéticos podem adiantar os testes automatizados e a cobertura real fica pendente até essa verificação.
- Exercitar captura com o app fora da tela, recuperação após encerramento do processo, uso offline, troca de conta, alertas repetidos, compras distintas de mesmo valor, falha após gravação no servidor e edição manual concorrente com a classificação. Conferir histórico e saldo em cada transição de sincronização.
- Medir no aparelho de referência latência, uso de memória, tamanho do modelo, disponibilidade com o app fora de primeiro plano e qualidade de classificação no conjunto de avaliação. Incluir exemplos ambíguos para verificar a abstinência. Se o FunctionGemma não passar na validação, manter o lançamento automático com **Sem categoria** e registrar quais limites impediram a categorização em segundo plano.
- O planejamento define o agendamento do processamento, a distribuição do modelo, a matriz de aparelhos e os limiares de avaliação com base nas medições e nas amostras disponíveis. A versão Expo continua disponível durante a adoção do cliente Android. O backend mantém compatibilidade com os lançamentos manuais existentes.
