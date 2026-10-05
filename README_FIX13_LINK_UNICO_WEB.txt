ÁGUA & GÁS EXPRESS — FIX13 — LINK ÚNICO + FALLBACK WEB

Versão: 13.0-test / versionCode 13

OBJETIVO
- Cada convite passa a ter UM ÚNICO link HTTPS.
- O mesmo link é usado no WhatsApp para empresa, cliente e entregador.
- Se o APK Android estiver instalado, o Android App Link abre o aplicativo diretamente.
- Se o APK não estiver instalado, o link cai na página Web de convite.
- Cliente e entregador recebem somente a ação de baixar o aplicativo Android.
- Empresa recebe a página com Android e acesso pelo Notebook/Web.

LINKS GERADOS
Empresa:
https://agua-e-gas-express.web.app/invite?perfil=empresa&empresa=CODIGO

Cliente:
https://agua-e-gas-express.web.app/invite?perfil=cliente&empresa=CODIGO

Entregador:
https://agua-e-gas-express.web.app/invite?perfil=entregador&empresa=CODIGO

IMPORTANTE SOBRE O DOWNLOAD DO ANDROID
A página aponta para:
https://agua-e-gas-express.web.app/downloads/AguaGasExpress.apk

Para o botão de download funcionar de verdade, o APK gerado pelo Android Studio precisa ser colocado na pasta:
public/downloads/AguaGasExpress.apk
antes de executar:

firebase deploy --only hosting

O projeto-fonte não contém um APK inventado. A compilação continua sendo feita no Android Studio do proprietário.

IMPORTANTE SOBRE NOTEBOOK
O projeto atual é um aplicativo Android. Portanto não existe um .EXE/.MSI de Windows para "baixar" neste pacote. O botão da empresa usa a Web no notebook. Para transformar o notebook em um aplicativo instalável, será necessário criar/publicar a versão Web/desktop correspondente.

NÃO ALTERADO
- Cadastro da empresa: Nome da empresa, E-mail autorizado, Telefone.
- Firebase/Firestore e regras atuais.
- Ícone verde com chama amarela e gota azul.
- Fluxo interno do aplicativo e perfis.
- assetlinks.json e App Links HTTPS.
