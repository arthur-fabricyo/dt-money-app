# DT Money Android

App Android nativo em Kotlin e Jetpack Compose. Usa o contrato do backend Fastify legado para login, cadastro, categorias, saldo e gestão de transações.

## Executar

Abra esta pasta no Android Studio com JDK 17 ou superior e Android SDK 36. O app roda no Android 8.1 ou superior. Para gerar o APK de desenvolvimento e executar os testes no Windows, rode:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`. O backend legado escuta na porta 3001. No emulador, use `http://10.0.2.2:3001`. Em um aparelho conectado por USB, rode `adb reverse tcp:3001 tcp:3001` e use `http://127.0.0.1:3001`. A versão de produção exige uma URL HTTPS.

## Importar compras

1. Entre com sua conta e abra **Capturas**.
2. Informe o identificador de pacote do aplicativo bancário e salve. Em **Configurações do Android**, conceda acesso às notificações ao DT Money.
3. Gere um alerta do banco, volte ao DT Money e toque em **Atualizar capturas**. Abra a compra para revisar a descrição e o valor, escolher uma categoria e salvar como saída.

A regra inicial reconhece textos como `Compra aprovada de R$ 42,90 em Mercado X` ou `Compra aprovada: R$ 42,90 em Mercado X`. Cada banco pode usar formatos diferentes. O serviço filtra pelo pacote indicado e guarda somente os campos extraídos, na fila SQLite da conta e da API em uso.

A fila exibe o horário da notificação. O backend legado registra a data de criação da saída ao salvar. Quando um envio fica sem confirmação, confira o histórico e escolha **Arquivar** ou **Voltar para revisão**.

O contrato para data da compra, importação idempotente e registro automático está no [KB do DT Money](https://chatgpt.com/space/page_6d4769017c088191ae81349352e7358d).
