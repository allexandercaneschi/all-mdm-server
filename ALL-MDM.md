# ALL MDM — painel

Fork do [Headwind MDM](https://github.com/h-mdm/hmdm-server) (Apache 2.0) com a marca da ALL Tecnologias.
O código da Headwind fica intacto; as mudanças se limitam à aparência, aos textos e ao nome do agente Android.

## O que foi alterado

| Arquivo | Mudança |
|---|---|
| `server/src/main/webapp/css/all-theme.css` | Tema escuro da ALL (cores, tipografia, raios), carregado depois do CSS original |
| `server/src/main/webapp/app/shared/all-theme.js` | Cores dos gráficos (Chart.js) no tema escuro |
| `server/src/main/webapp/fonts/` | Inter e JetBrains Mono embutidas (licença OFL) |
| `server/src/main/webapp/images/` | Logo, favicon e bolinhas de status com as cores da ALL |
| `server/src/main/webapp/index.html` | Título "ALL MDM" e inclusão do tema |
| `server/src/main/webapp/localization/*.js` | Nome "ALL MDM" e fornecedor "ALL Tecnologias" |
| `server/src/main/webapp/localization/pt_PT.js` | Textos adaptados para o português do Brasil (o navegador em pt-BR usa este arquivo) |
| `server/src/main/webapp/app/components/main/controller/summary.controller.js` | Cores do semáforo nos gráficos do resumo |
| `server/src/main/webapp/app/components/main/controller/configurations.controller.js` | Reconhece o agente ALL (`br.com.alltecnologias.mdm`) ao criar configurações |
| `.github/workflows/build-war.yml` | Compila o WAR e publica no Releases |
| `ALL_VERSION` | Versão da ALL (`X.Y.Z`); mude aqui para lançar uma versão nova. A base Headwind vai na descrição da release |

Para trocar cores, edite só as variáveis no topo de `all-theme.css`.

## Gerar uma versão

1. Altere o arquivo `ALL_VERSION` (formato `X.Y.Z`, ex.: `1.0.1`) e faça push na branch `all/main`.
2. O GitHub Actions compila, cria a tag `v1.0.1` e publica `all-mdm-1.0.1.war` no Releases.
   Pushes que não mudam o `ALL_VERSION` só compilam (o WAR fica como artefato do workflow, para teste).
3. No repositório `all-mdm` (Docker), aponte `HMDM_URL` para esse arquivo e suba a versão de lá também.

## Atualizar com a Headwind

1. No GitHub, use **Sync fork** na branch `master`.
2. Faça merge de `master` em `all/main` e resolva conflitos (em geral só em `pt_PT.js` e `index.html`).
3. Suba o número em `ALL_VERSION` e faça push.
