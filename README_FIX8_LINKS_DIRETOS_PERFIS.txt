ÁGUA & GÁS EXPRESS — FIX8 — LINKS DIRETOS DE PERFIL

Versão: 12.0-test / versionCode 12

OBJETIVO
- Manter o App Link HTTPS verificado como endereço oficial.
- Ao tocar no link do CLIENTE, abrir o aplicativo e vincular automaticamente à empresa.
- Ao tocar no link do ENTREGADOR, abrir o aplicativo e vincular automaticamente à empresa.
- Não mostrar mais a antiga tela com CLIENTE / ADMINISTRAÇÃO / ENTREGADOR como etapa intermediária do convite.
- Se o aplicativo já estiver aberto, receber um novo convite sem precisar fechar o app.

COMPORTAMENTO
Empresa:
https://agua-e-gas-express.web.app/invite?perfil=empresa&empresa=CODIGO&plataforma=android
-> abre o fluxo da Empresa.

Cliente:
https://agua-e-gas-express.web.app/invite?perfil=cliente&empresa=CODIGO&plataforma=android
-> abre o app, consulta a empresa pelo código e entra direto no perfil Cliente.

Entregador:
https://agua-e-gas-express.web.app/invite?perfil=entregador&empresa=CODIGO&plataforma=android
-> abre o app, consulta a empresa pelo código e entra direto no perfil Entregador.

ALTERAÇÕES DESTA VERSÃO
1. MainActivity passa a processar convites também em onNewIntent().
2. A Activity usa singleTop para receber novos links enquanto já está aberta.
3. Cliente/Entregador limpam o papel anterior do aparelho antes de aplicar o novo convite.
4. Cliente/Entregador são vinculados automaticamente ao documento da empresa, sem botão ABRIR CONVITE.
5. Mantidos Firebase/Firestore, cadastro da empresa e ícone existentes.

IMPORTANTE
- O assetlinks.json existente não foi alterado.
- O domínio deve continuar aparecendo como verified no Android.
- Este pacote é o projeto-fonte para abrir no Android Studio e gerar o APK.
- Nesta máquina o Gradle não conseguiu baixar o wrapper por falta de acesso à internet; portanto nenhum APK foi inventado ou declarado como compilado aqui.
