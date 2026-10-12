# Auditoria funcional inicial — Água & Gás Express

Data: 2026-10-12  
Repositório: `Alberesbet/agua-gas-express-v13`  
Base inspecionada: branch `master`, commit `e4ce546a0783bc74dc4487b4b917ce2056ec9569`.

## Resumo

O projeto contém aplicativo Android em Kotlin/Jetpack Compose, quatro variantes de APK (desenvolvedor, empresa, cliente e entregador), páginas de convite/painel Web e integração direta com Firebase Authentication/Firestore. O fluxo descrito nos documentos existe parcialmente, mas há inconsistências que impedem considerar o sistema pronto para produção.

## Bloqueadores encontrados

### 1. Credenciais diferentes no Android e na Web

- O cadastro/login da empresa no Android grava `adminPasswordHash` no documento `companies` e compara esse hash localmente no login.
- O painel Web `invite/empresa/index.html` usa `createUserWithEmailAndPassword` e `signInWithEmailAndPassword` do Firebase Authentication.
- Portanto, a senha cadastrada no Android não cria necessariamente uma conta Firebase Authentication; a mesma combinação e-mail/senha pode não funcionar no painel Web, e vice-versa.

**Correção necessária:** unificar o acesso da empresa em Firebase Authentication (e-mail/senha) nos dois clientes, migrar/recuperar contas antigas com cuidado e persistir o UID autenticado da empresa. Não apagar o mecanismo antigo até existir um caminho de migração testado.

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

O código é gerado localmente com seis caracteres hexadecimais derivados de UUID, sem uma verificação explícita de colisão antes de gravar o documento. Os fluxos usam esse código para localizar a empresa no Firestore.

**Correção necessária:** gerar o código no backend ou fazer uma reserva transacional com verificação de unicidade; validar o código no servidor e nunca tratar o código de convite como prova de identidade. O código compartilhado deve vincular o usuário à empresa, mas a autorização final precisa depender da conta autenticada e das regras.

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
