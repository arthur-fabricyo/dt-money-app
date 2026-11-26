# Visual do DT Money Android em Compose

## Objetivo

Trazer o visual do cliente React Native para todas as telas e componentes do cliente Android Kotlin. Usar o React Native do repositório como referência para identidade visual, hierarquia, espaçamentos, estados e interações. O aplicativo nativo mantém a API configurável, o registro local de movimentações, a sincronização e a captura com IA local.

## Abordagens

O usuário pediu o visual anterior em todos os componentes. Reproduzir os componentes do repositório em Compose permite usar o mesmo logotipo, paleta e organização das telas com os dados e fluxos do Android atual. Aplicar apenas as cores ao Material atual deixaria os layouts e controles diferentes do que foi pedido. Incorporar telas Expo ao Android acrescentaria outra camada de execução sem um benefício para a adaptação visual.

## Telas e componentes

| Área | Referência no React Native e adaptação em Compose |
| --- | --- |
| Identidade | Usar o logotipo existente em `dt-money-app/src/assets/Logo.png`, a fonte Roboto e as cores de `dt-money-app/src/shared/colors.ts`. Criar tema compartilhado com superfícies `#121214`, `#202024` e `#29292E`, ações em verde e saídas em vermelho. Preservar contraste suficiente em textos, ícones e estados desabilitados. |
| Login e cadastro | Reproduzir cabeçalho com logo, largura centralizada, campos, botões e espaçamento dos formulários. Integrar URL da API ao formulário com o mesmo estilo. Manter validação e mensagens do Android. |
| Painel | Reproduzir cabeçalho com logo e ações, cartões horizontais de saídas, entradas e total, busca, filtros e cartões de transação com categoria e data. Mostrar sincronização pendente e saldo parcial na hierarquia dos cartões. Manter acesso a Capturas e às ações de edição, categorização e exclusão. |
| Transações e filtros | Usar o padrão do React Native para editar e criar transações, escolher entrada ou saída, escolher categorias, filtrar por tipo e datas e confirmar uma exclusão. Reproduzir painéis inferiores e gestos de deslizar quando a referência os usa. Oferecer também ações equivalentes acessíveis sem gesto. |
| Capturas e modelo local | Usar os mesmos cartões, campos, seletores e botões para permissões, ativação dos bancos, instalação do FunctionGemma, revisão e descarte. Mostrar os dados sugeridos como pendentes de confirmação e respeitar o fluxo offline. |
| Estados de tela | Aplicar o tema a carregamento, mensagens, erros, listas vazias, diálogos, teclado e barras do sistema. Garantir rolagem e acesso aos controles em telas estreitas e com texto ampliado. |

O planejamento define dimensões por componente a partir do código JSX e como distribuir os composables. Os ícones dos fluxos já existentes devem corresponder visualmente aos da referência, com rótulos de acessibilidade quando comunicam uma ação. Os fluxos exclusivos do Kotlin adotam os mesmos componentes. Mudanças no modelo de IA, no backend e nas regras de importação ficam fora deste redesenho.

## Verificação e APK

- Comparar login, cadastro, painel, cartões, filtros e formulários com as respectivas referências em React Native no mesmo tamanho de tela. Conferir os estados exclusivos de Capturas no Android.
- Exercitar login, criar e editar transação, navegar em Capturas, revisar e descartar um alerta e conferir os indicadores offline. Usar dados de teste para conferir espaçamento e hierarquia.
- Executar os testes unitários do Android, lint e build do APK debug. Verificar a assinatura do APK e copiar o arquivo para a pasta indicada pelo usuário. Conferir a existência da cópia final e seu hash.
- Usar o aparelho do usuário para instalação e teste quando ele estiver autorizado no ADB. Parear pelo Wi‑Fi ou USB conforme a preferência e a disponibilidade. O build e a cópia do APK podem ser feitos antes do pareamento.
