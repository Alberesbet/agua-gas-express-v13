FIX7 — LINKS HTTPS + PERFIS + CONTROLE DE LICENÇA

1. Link canônico de convite: https://agua-e-gas-express.web.app/invite
2. Empresa: perfil=empresa&empresa=CODIGO&plataforma=android ou notebook
3. Cliente: perfil=cliente&empresa=CODIGO
4. Entregador: perfil=entregador&empresa=CODIGO
5. Removido o filtro de esquema aguagasexpress do Manifest. O HTTPS é a entrada principal.
6. O assetlinks.json permanece necessário no Hosting e deve ser publicado com:
   firebase deploy --only hosting
7. No painel do proprietário, em informações da empresa:
   - ENCERRAR TESTE E TORNAR DEFINITIVA
   - acrescentar dias ao teste
8. Senhas: campos de senha passam a ter MOSTRAR/OCULTAR também no cadastro/login.
9. Convites de cliente/entregador entram diretamente no perfil correspondente; não devem mostrar a escolha Cliente/Entregador.
