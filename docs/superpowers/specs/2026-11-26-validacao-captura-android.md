# Validação de notificações bancárias no Android

## Ambiente e cobertura

- Os identificadores dos aplicativos brasileiros foram conferidos nas listagens oficiais da Play Store. [Nubank](https://play.google.com/store/apps/details?id=com.nu.production&hl=pt_BR) · [Santander](https://play.google.com/store/apps/details?id=com.santander.app&hl=pt_BR)
- Os testes de regras usam exemplos sintéticos de Pix recebido, Pix enviado, crédito, débito, compra de meio desconhecido e alertas que devem ser ignorados. Os testes de conciliação cobrem registro local, reenvio ambíguo, categoria manual e duplicação de saldo.
- Os handlers Fastify são testados com SQLite em memória. Os testes de integração exercitam migração, repetição concorrente, transação fora da página, exclusão, categoria manual e sessão expirada.
- Nenhum dispositivo apareceu em `adb devices`. A execução da suíte instrumentada, a coleta de textos reais dos dois bancos e a medição dos modelos no Android ficam pendentes.
- A configuração experimental requer ativação por banco no APK de desenvolvimento. Os formatos não foram habilitados para lançamento automático no APK de produção.

## Verificação pendente no aparelho

1. Para cada banco, registrar exemplos anonimizados de texto e título para as movimentações cobertas e para alertas fora do escopo.
2. Com a tela em outro app, verificar cada publicação nova, valores, direção, data, meio, reenvio após interrupção e ausência de duplicidade.
3. Definir os critérios de aceitação da categorização antes da avaliação final. Usar exemplos rotulados que não entraram no ajuste do modelo. Registrar latência, memória, tamanho do artefato, precisão das sugestões aceitas, cobertura e abstinência.
4. Repetir com conexão indisponível, processo reiniciado, troca de sessão e categoria editada durante o processamento. Confirmar a consistência entre histórico e saldo antes e depois da sincronização.
