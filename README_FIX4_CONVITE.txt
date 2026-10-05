AGUA & GAS EXPRESS - FIX4 CONVITE

1) Extraia o ZIP inteiro para uma pasta simples, por exemplo:
   C:\AguaGasExpress_FIX4

2) Abra essa pasta no Explorador e confirme que nela existem:
   firebase.json
   .firebaserc
   public\invite\index.html
   deploy-hosting.bat

3) Execute o arquivo:
   deploy-hosting.bat

   OU, no CMD dentro dessa mesma pasta:
   firebase deploy --only hosting

4) O deploy deve terminar com:
   Deploy complete!
   Hosting URL: https://agua-e-gas-express.web.app

5) Depois do deploy, abra no Android o mesmo link do convite.
   A pagina nao tenta mais abrir o aplicativo sozinha.
   E necessario tocar em ABRIR NO APLICATIVO.
   O primeiro toque usa diretamente:
   aguagasexpress://invite?... 
   Se o navegador bloquear, o codigo tenta uma URI intent como segunda opcao.

IMPORTANTE:
- Esta correcao preserva o projeto Android e o fluxo existente.
- Nao altera cadastro do desenvolvedor, cadastro da empresa, detalhes da empresa ou Firebase Firestore.
- A pagina de convite e o deploy do Hosting sao os pontos corrigidos.
