AGUAGASEXPRESS — PRÓXIMA VERSÃO

O painel do proprietário agora cadastra NOME DA EMPRESA + E-MAIL AUTORIZADO + TELEFONE. O e-mail continua sendo a autorização para a empresa abrir a conta.

Ao tocar no nome da empresa no painel, abre a tela de informações com licença, situação, dias restantes, vencimento, versão autorizada e código interno.

A troca do e-mail autorizado está disponível enquanto a empresa ainda não criou a conta. Para empresa já autenticada, a troca do e-mail de login precisa ser feita pela conta autenticada, para não quebrar o acesso do Firebase Authentication.

Os links compartilhados agora usam HTTPS: https://agua-e-gas-express.web.app/invite?... para serem reconhecidos como clicáveis no WhatsApp.

IMPORTANTE: o endereço HTTPS precisa estar publicado no Firebase Hosting. Este projeto já inclui firebase.json, .firebaserc e public/invite/index.html. No diretório do projeto, com Firebase CLI autenticado, publique com:

firebase deploy --only hosting

As regras do Firestore continuam separadas: firebase deploy --only firestore:rules

Não altere a base Firebase que já está funcionando sem necessidade.

### FIX13 — LINK ÚNICO

Agora os convites usam um único HTTPS por perfil, sem separar Android/Notebook no link. Com o APK instalado, o App Link abre o aplicativo. Sem APK, abre a página Web de fallback.

A página Web do convite aponta para `public/downloads/AguaGasExpress.apk`. Coloque ali o APK gerado pelo Android Studio e publique o Hosting.
