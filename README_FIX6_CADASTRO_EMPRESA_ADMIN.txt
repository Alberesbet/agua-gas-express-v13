FIX6 — CORREÇÃO DO FLUXO APÓS CADASTRO DA EMPRESA

Correção feita sobre o FIX4_CONVITE:
- O convite HTTPS e App Links permanecem como no FIX5.
- O cadastro da empresa agora grava company_role=admin.
- Após finalizar o primeiro cadastro pelo convite autorizado, o aplicativo entra DIRETAMENTE na área administrativa da empresa.
- A preferência de entrada direta é usada somente na primeira abertura após o cadastro; em aberturas futuras, o fluxo normal de segurança permanece.
- Não foram alterados Firestore rules, ícone, dados do cadastro ou links de cliente/entregador.

IMPORTANTE:
Este pacote é um projeto Android Studio. Gere o APK no Android Studio.
O assetlinks.json continua apontando para o certificado SHA-256 do APK debug informado no projeto.
