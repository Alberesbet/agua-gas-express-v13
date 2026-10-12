# Auditoria funcional inicial — Água & Gás Express

Data: 2026-10-12  
Repositório: `Alberesbet/agua-gas-express-v13`  
Base inspecionada: branch `master`, commit `e4ce546a0783bc74dc4487b4b917ce2056ec9569`.

## Resumo

O projeto contém aplicativo Android em Kotlin/Jetpack Compose, quatro variantes de APK (desenvolvedor, empresa, cliente e entregador), páginas de convite/painel Web e integração direta com Firebase Authentication/Firestore. O fluxo descrito nos documentos existe parcialmente, mas há inconsistências que impedem considerar o sistema pronto para produção.

## Bloqueadores encontrados

### 1. Credenciais diferentes no Android e na Web

- O cadastro/login da empresa no Android antes comparava `adminPasswordHash` localmente no Firestore; o painel Web já usava Firebase Authentication.
- A branch agora tenta unificar Android e Web em Firebase Authentication, faz migração gradual de senhas antigas e exige confirmação do e-mail antes de concluir o cadastro/login.
- O Android verifica se `authUid` já está vinculado a outra conta antes de gravar. A Web também confere esse campo antes de abrir o painel.

**Limite de segurança:** essas verificações de cliente, sozinhas, não protegem o banco contra chamadas diretas ao Firestore. Como as regras atuais ainda permitem leitura/gravação global para qualquer usuário autenticado, o vínculo `authUid` só será uma barreira real quando regras ou backend impedirem que o cliente altere arbitrariamente documentos de empresas.

### 2. Regras do Firestore não isolam empresas nem perfis

O arquivo `firestore.rules` permite `read` e `write` em todos os documentos para qualquer usuário autenticado. O aplicativo inicia sessões anônimas em vários fluxos, então autenticação não equivale a autorização. Um usuário anônimo poderia, conforme as regras publicadas, ler ou alterar documentos de outras empresas.

**Correção necessária:** desenhar regras por coleção e operação, verificar o UID/empresa do usuário e restringir dados de clientes, pedidos, configurações, licenças e administração. A administração global do desenvolvedor deve ser movida para um backend confiável (por exemplo, Cloud Functions com privilégios de servidor/custom claims); não confiar em um segredo ou senha apenas dentro do APK. Não publicar regras restritivas sem testar todas as telas, pois as operações atuais do app dependem de acesso amplo.

### 3. Domínios de convite inconsistentes

- O aplicativo Android monta convites usando `https://alberesbet.github.io/agua-gas-express-v13/invite/`.
- A configuração Firebase inclui `public/invite/index.html` e `firebase.json`, e a documentação também descreve `https://agua-e-gas-express.web.app/invite`.
- O Manifest contém filtros para ambos os domínios, mas o filtro do Firebase Hosting não tem `autoVerify=true`.
- O arquivo `public/.well-known/assetlinks.json` declara somente o package `com.example.aguagasexpress`, enquanto o Gradle produz IDs diferentes: `.desenvolvedor`, `.empresa`, `.cliente` e `.entregador`.

**Correção necessária:** escolher um domínio canônico, alinhar a geração de links e as páginas de fallback, marcar o filtro HTTPS correto para verificação e publicar Digital Asset Links para cada package ID real e certificado de assinatura usado nos APKs distribuídos. A impressão digital do certificado de release precisa ser confirmada antes de alterar a associação.

### 4. Downloads de APKs inconsistentes

As páginas apontam para arquivos separados como `AguaGasExpress-Cliente.apk`, `AguaGasExpress-Entregador.apk`, `AguaGasExpress-Empresa.apk` e `AguaGasExpress-Desenvolvedor.apk`. Na árvore pública do repositório, o único APK versionado em `public/downloads` é `AguaGasExpress.apk`.

**Correção necessária:** compilar as quatro variantes e publicar cada APK com o nome esperado, ou alterar as páginas para apontar para arquivos que realmente existem. Não reutilizar o APK genérico para todos os perfis sem confirmar o application ID e o perfil incorporado.

### 5. Código da empresa

O código é gerado localmente com seis caracteres hexadecimais derivados de UUID. A alteração inicial nesta branch agora consulta o Firestore antes de gravar e tenta outro código se encontrar um já cadastrado, com até cinco tentativas.

**Limite importante:** essa consulta prévia reduz a chance de colisão, mas não é uma reserva atômica; dois cadastros simultâneos ainda podem, em teoria, escolher o mesmo código. A correção definitiva é gerar/reservar o código no backend ou por transação com verificação de unicidade; validar o código no servidor e nunca tratá-lo como prova de identidade. O código compartilhado deve vincular o usuário à empresa, mas a autorização final precisa depender da conta autenticada e das regras.

### 6. Fluxo de cliente/entregador

As páginas Web gravam clientes em `customers` após autenticação anônima e resolução do código para o ID real da empresa. O app também usa autenticação anônima e lê documentos de empresa/pedidos. Isso depende diretamente das regras amplas atuais e precisa ser testado novamente após o isolamento de permissões.

## Sequência de correção recomendada

1. Unificar a autenticação da empresa entre Android e Web, incluindo migração de contas antigas.
2. Implementar autorização e isolamento multiempresa no Firestore/backend; nunca publicar regras restritivas sem uma matriz de testes por perfil.
3. Escolher um domínio canônico e corrigir App Links/Digital Asset Links para os quatro application IDs e os certificados efetivos.
4. Gerar/publicar os quatro APKs com nomes consistentes e validar cada link de download.
5. Testar ponta a ponta: desenvolvedor cria empresa → código único → empresa se registra → empresa gera links de cliente/entregador → cada perfil entra apenas na empresa correta → pedidos/configurações ficam isolados.
6. Executar build Android e testes de regras antes de publicar no Firebase Hosting/Firestore.

## Limite desta auditoria

Esta é uma revisão estática do código e da documentação no repositório. Ainda não prova que o projeto Firebase publicado tem as mesmas regras/configurações nem que os APKs atuais compilam e abrem corretamente. Não foram publicadas alterações no Firebase nem trocadas regras de produção nesta etapa.


## Alterações iniciais propostas nesta branch

Em `fix/auditoria-funcional-inicial` foram feitos commits para aproximar o acesso Android e Web de uma identidade única Firebase Authentication:

- O cadastro Android da empresa tenta criar a conta por e-mail/senha no Firebase Authentication e grava o UID no documento da empresa (`authUid`).
- Se a conta já existir, o cadastro tenta autenticar com a mesma senha antes de gravar os dados.
- O login Android passa a autenticar pelo Firebase Authentication e exige e-mail verificado. Para contas antigas que ainda têm apenas `adminPasswordHash`, existe uma tentativa de migração gradual, seguida de confirmação do e-mail.
- Cadastro e login Android recusam substituir um `authUid` diferente e recusam ativar um registro já ativado sem vínculo.
- O painel Web exige e-mail verificado e confere o campo `authUid` antes de abrir o painel.

### Testes ainda necessários antes de mesclar/publicar

1. Compilar todas as variantes Android, em especial `assembleDesenvolvedorDebug`, `assembleEmpresaDebug`, `assembleClienteDebug` e `assembleEntregadorDebug`. O workflow foi configurado para executar em pushes desta branch e em pull requests para `main/master`.
2. Testar primeiro acesso Android com empresa ainda não vinculada, acesso Web com conta já criada e migração de conta antiga.
3. Testar casos de senha errada, e-mail já cadastrado com outra senha, e-mail sem autorização, código incorreto e empresa já vinculada a outro UID.
4. Validar se as regras atuais do Firestore permitem as operações e preparar regras de menor privilégio com testes automatizados antes de qualquer publicação.
5. Confirmar se o Firebase Authentication por e-mail/senha está habilitado no projeto de produção e testar a confirmação de e-mail no Android e Web.
6. Depois, implementar regras/backend seguros; não tratar verificações do front-end como autorização do banco.

**Importante:** as alterações acima estão numa branch/PR e ainda não foram compiladas ou testadas contra o projeto Firebase de produção. Não publique regras de Firestore permissivas para “fazer funcionar”; isso deixaria os dados de todas as empresas expostos a qualquer usuário autenticado.
