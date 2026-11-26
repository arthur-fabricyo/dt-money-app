# DT Money Android

App Android nativo em Kotlin e Jetpack Compose. Usa o contrato do backend Fastify legado para login, cadastro, categorias, saldo e gestão de transações.

## Executar

Abra esta pasta no Android Studio com JDK 17 ou superior e Android SDK 36. O app roda no Android 8.1 ou superior. Para gerar o APK de desenvolvimento e executar os testes no Windows, rode:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`. Envie esse arquivo ao celular e abra-o para instalar. Configure o backend e rode as migrations conforme o [guia do backend](../DT-money-backend/README.md).

### Celular físico por Wi-Fi, sem USB

1. Conecte o celular e o computador à mesma rede Wi‑Fi. No Windows, execute `ipconfig` e copie o **Endereço IPv4** de **Adaptador de Rede sem Fio Wi-Fi**.
2. Em `DT-money-backend/.env`, adicione `DT_MONEY_HOST` com o IP encontrado, por exemplo `DT_MONEY_HOST=192.168.1.42`. Na pasta `DT-money-backend`, inicie ou reinicie a API com `npm run dev`.
3. No navegador do celular, abra `http://192.168.1.42:3001/docs`, substituindo o IP pelo do seu computador. Se a página não abrir, confira se a API está rodando e se o firewall permite conexões pela rede privada na porta TCP 3001.
4. No login do DT Money, preencha **URL da API** com `http://192.168.1.42:3001`, usando o mesmo IP. A URL do login termina na porta `3001`.

### Emulador ou USB

| Ambiente | URL da API no login |
| --- | --- |
| Emulador do Android Studio com o backend em `localhost` | `http://10.0.2.2:3001` |
| Celular por USB com o backend em `localhost`, após `adb reverse tcp:3001 tcp:3001` | `http://127.0.0.1:3001` |

Use o APK debug para testar com HTTP. A versão de produção exige HTTPS.

## Lançamentos automáticos no APK de desenvolvimento

1. Confirme que aplicou as migrations do backend. A migration de importações adiciona unicidade por usuário e a categoria **Sem categoria**.
2. Entre no app e abra **Capturas**. Em **Configurações do Android**, conceda acesso a notificações ao DT Money. Ative Nubank e Santander individualmente.
3. Para usar FunctionGemma 270M, toque em **Instalar FunctionGemma local** e selecione um modelo `.litertlm` que tenha suporte a chamadas de função. O binário fica no aparelho. A documentação do Google descreve a preparação e a exportação do modelo. [FunctionGemma e LiteRT-LM](https://ai.google.dev/gemma/docs/mobile-actions)
4. Abra **Transações** para ver os lançamentos. Use **Alterar categoria** para corrigir uma sugestão ou categorizar manualmente um item.

Alertas reconhecidos vão para o SQLite mesmo sem rede. Pix recebido é entrada. Pix enviado, crédito e débito aprovados são saídas. Uma compra aprovada que omite o meio mantém o meio desconhecido. O saldo é parcial quando só há dados locais ou quando a API ainda não confirmou o estado de um envio.

Quando o app encontra um formato novo do Nubank ou Santander, o FunctionGemma tenta interpretar o alerta no aparelho. Em aparelhos compatíveis, o Gemini Nano é a alternativa em primeiro plano. O app só lança automaticamente se o alerta confirmar o valor em reais, a direção e a conclusão da movimentação. Se a interpretação estiver indisponível ou faltar evidência, os alertas financeiros ficam para revisão em **Capturas**. Ali você pode confirmar entrada ou saída, corrigir os campos ou descartar. Uma revisão só entra no saldo após a confirmação. O texto da notificação fica em memória durante a análise. [Restrição de segundo plano do ML Kit](https://developers.google.com/ml-kit/genai/#background_usage)

Os pacotes de origem são os aplicativos brasileiros oficiais do [Nubank](https://play.google.com/store/apps/details?id=com.nu.production&hl=pt_BR) e do [Santander](https://play.google.com/store/apps/details?id=com.santander.app&hl=pt_BR). Os testes automatizados incluem os alertas Santander de Pix enviado e recebido com data e hora observados no Android e exemplos sintéticos para formatos novos. A avaliação dos dois modelos em aparelho e com alertas reais dos dois bancos ainda está pendente.

## Revisar capturas anteriores

O campo **Pacote para revisão manual** mantém o fluxo legado de compras. Capta alertas como `Compra aprovada de R$ 42,90 em Mercado X` e deixa a compra em **Capturas** para confirmação. A versão anterior da fila, inclusive envios de resultado incerto, continua disponível para revisão.
