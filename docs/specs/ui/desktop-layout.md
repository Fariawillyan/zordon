---
document: ui-desktop-layout
module: ui
section: desktop-layout
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [desktop,layout,navegacao,chat,estados,javafx]
specId: null
---

# Layout desktop inicial

## 1. Objetivo e decisão

Organizar o Zordon em **navegação à esquerda, trabalho no centro e contexto à
direita**, com comando sempre acessível. Recomendação inicial **DRAFT**, derivada
do [design system](design-system.md) e da [arquitetura desktop](design.md).

A tela central deve responder: o que pedi, o que está acontecendo, o que já foi
feito e o que depende de mim. O contexto lateral responde: quais recursos estão
disponíveis e quanto está sendo consumido.

[Abrir prévia navegável](preview/index.html). Os dados e ações da prévia são
ilustrativos, locais e sem conexão com a máquina.

## 2. Composição e medidas

Referência: área cliente de **1600 × 900 unidades lógicas**. O desenho também
deve funcionar em notebooks e janelas divididas; não exigir maximização.

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ Barra de título nativa — Zordon                     minimizar / max / X │
├─────────────────────────────────────────────────────────────────────────┤
│ ZORDON   Núcleo conectado · WSL     Voz: desligada    Alertas   Pausar  │
├───────────────┬───────────────────────────────────────┬─────────────────┤
│ TRABALHO      │ Chat / sessão                         │ CONTEXTO        │
│ Início        │ Containers do projeto   Nova conversa │                 │
│ Chat          ├───────────────────────────────────────┤ Segurança       │
│ Voz           │ Você                                  │ Estado e avisos │
│               │ Verifique meus containers.            │                 │
│ RECURSOS      │                                       │ Sistema · WSL   │
│ Agentes       │ Zordon · SystemAgent                  │ CPU/RAM/GPU/HD  │
│ MCP           │ ✓ Consulta concluída                  │ + última coleta │
│ Skills        │ ┌───────────────────────────────────┐ │                 │
│ Automações    │ │ Resultado do Docker              │ │ Execução atual  │
│ Memória       │ │ Nome       Estado   Portas       │ │ Agente / etapa  │
│ Conhecimento  │ │ api        ativo    3000         │ │                 │
│               │ └───────────────────────────────────┘ │ Conexões MCP    │
│ OPERAÇÃO      │ Ver logs · Ver detalhes               │                 │
│ Sistema       ├───────────────────────────────────────┤ Uso / orçamento │
│ Uso           │ Agente automático · Contexto          │                 │
│ Logs          │ Peça uma tarefa ao Zordon…            │                 │
│ Segurança     │ Anexar · Voz                  Enviar  │                 │
│ Diagnóstico   └───────────────────────────────────────┴─────────────────┤
│ Configurações │ WSL · Host Windows · sincronização · tarefa em andamento │
└───────────────┴─────────────────────────────────────────────────────────┘
```

| Região | Medida | Comportamento |
|---|---:|---|
| Título do sistema operacional | Nativa | Preservar mover, redimensionar, Snap, atalhos e controles do Windows |
| Cabeçalho do aplicativo | 56 | Marca, conexão, voz, notificações e acesso a pausar |
| Navegação | 224 expandida / 72 recolhida | Grupos roláveis; Configurações ao final |
| Centro | Elástico | Prioridade de largura; leitura limitada a 80 caracteres quando textual |
| Inspector | 304 | Informativo, rolável, recolhível; links abrem a tela responsável |
| Espaçamento externo e entre colunas | 16 | Em modo compacto, externo 12 |
| Composer | Mínimo 112, cresce até 200 | Pertence ao centro; conteúdo rola acima dele |
| Barra de estado | 28 | Resumo, última sincronização e acesso a diagnóstico |

No modo completo: `centro = largura − 224 − 304 − 2×16 − 2×16`.
A 1600, o centro recebe 1008; a 1440, 848. A barra de título nativa fica
fora da área cliente e sua altura não é fixada pelo design.

### Adaptação à janela

| Largura lógica | Navegação | Inspector | Centro |
|---|---|---|---|
| ≥ 1440 | 224, recolhível | 304 aberto por padrão | Conversa, tabela ou tarefa |
| 1100–1439 | 224, recolhível | Fechado; botão `Contexto` abre drawer | Prioridade ao trabalho |
| 880–1099 | Trilho 72 | Drawer | Nomes completos por tooltip e nome acessível |
| < 880 ou texto ampliado | Menu em drawer | Drawer | Uma coluna; ações quebram linha |

As faixas são escolhas do Zordon. Reorganizar e recolher regiões segue o
princípio de adaptação ao espaço disponível descrito pela
[Microsoft](https://learn.microsoft.com/en-us/windows/apps/design/layout/responsive-design).
Nunca esconder alertas, cancelamento, voz ou permissão ao recolher uma região.
Em janela baixa, navegação, conteúdo e inspector rolam independentemente;
composer permanece alcançável. Diálogo alto rola internamente com decisões visíveis.
Tabelas largas têm rolagem própria; o shell não ganha rolagem horizontal.

## 3. Navegação e arquitetura de informação

| Grupo | Destino | Conteúdo principal | Marco |
|---|---|---|---|
| Trabalho | Início | Retomar sessão, tarefas em andamento, pendências, eventos recentes e atalhos | M1; módulos ampliam depois |
| Trabalho | Chat | Conversas, sessões, streaming e resultados de ferramentas | M1 |
| Trabalho | Voz | Modo, microfone, dispositivo, transcrição e teste | M2 |
| Recursos | Agentes | Abas Assistente / Engenharia; execuções, escopo e orçamento | M5 / M8 |
| Recursos | MCP | Conexões, ferramentas, erro e revisão de mudança de superfície | M4 |
| Recursos | Skills | Capacidades nativas e seus efeitos/risco base | M3 |
| Recursos | Automações | Rotinas, gatilhos, próxima execução e histórico | M6 |
| Recursos | Memória | Fatos, procedência, correção e esquecimento | M5 |
| Recursos | Conhecimento | Documentação RAG, fontes, versão e estado do índice | M8; contrato pendente |
| Operação | Sistema | Métricas com origem, processos, Docker e WSL | M3, completo M6 |
| Operação | Uso | Hoje / 7 dias / 30 dias; por agente, modelo, tarefa e projeto | M8 |
| Operação | Logs | Timeline filtrável e correlação com execução/auditoria | M1, amplia nos marcos seguintes |
| Operação | Segurança | Incidentes, quarentena, disjuntores, exceções e regras temporárias | M3 mínimo; completo M7 |
| Operação | Diagnóstico | Saúde por processo, latências, reconexão, cache e RAG | M0 mínimo; completo M6/M8 |
| Rodapé da navegação | Configurações | Provider, áudio, preferências, orçamento e políticas editáveis permitidas | Progressivo |

Segurança fica entre Logs e Configurações na ordem geral, conforme a norma;
Diagnóstico é um destino adicional nessa região. Memória do usuário e
conhecimento documental não são intercambiáveis. `Uso` é o rótulo PT-BR da tela
`Zordon > Usage`; `Diagnóstico` corresponde a `Diagnostics`.

Itens de marcos futuros não simulam funcionalidade: no produto inicial ficam
ocultos ou identificados como indisponíveis, com motivo. Capacidades negociadas
e estado retornado pelo núcleo governam disponibilidade; não a existência do botão.

## 4. Início e Chat

**Início** é a tela de retomada, sem duplicar o histórico de Chat. Mostra
pendências reais primeiro, depois tarefas ativas, última conversa e até quatro
atalhos. Primeiro uso: apresentação curta e passos para conectar núcleo,
configurar provider e, opcionalmente, habilitar voz. Sem lista fictícia de agentes
online ou promessa de proteção ativa.

**Chat** tem cabeçalho da sessão, conversa virtualizada, resultados tipados e
composer. A imagem recebida corresponde principalmente a este destino: não
misturar aba Chat selecionada com item Início ativo.

O resultado de containers mostra origem Docker/WSL, horário da consulta, total,
nome, estado textual, portas e tempo quando fornecidos. O usuário pode abrir
logs e detalhes; o dado técnico permanece distinto da interpretação do modelo.
Sem dados estruturados no contrato, renderizar resumo textual honesto; não
extrair uma tabela operacional de texto livre do LLM e tratá-la como autoridade.

`Reiniciar containers` não é ação primária de consulta: fica em fluxo explícito
no contexto correto, com alvos resolvidos e permissão do núcleo. Atalhos que
executam ferramentas iniciam um turno; a UI não ganha `tool.invoke`.

Quando o usuário sobe no histórico, streaming não o leva de volta ao fim.
Mostrar `Novas mensagens`. Cancelar preserva a saída parcial e aguarda confirmação
do núcleo. Abrir nova conversa não remove a anterior nem interfere na auditoria.

### Composer global

Em Chat, envia para a sessão visível. Em outras telas, usa um rascunho global:
ao enviar, abre Chat e uma sessão para a nova tarefa. Sempre mostrar o destino
e contexto antes do envio. Navegação preserva rascunhos por sessão; não reutilizar
silenciosamente anexos de outra conversa.

Enter envia; Shift+Enter insere linha. Durante composição IME, Enter não envia.
Campo vazio não envia. Em execução, `Cancelar tarefa` é distinto de `Parar voz`.
Seleção explícita de agente prevalece sobre roteamento, mas depende de extensão
do contrato de `chat.send` registrada no [review](design-review.md).

Anexos mostram nome, origem, tamanho, estado de leitura e remoção antes do envio.
Informar provider local/remoto quando isso afetar o destino dos dados. Não alegar
que tudo fica local quando a conversa usa IA remota. Nunca oferecer shell livre.

## 5. Inspector e cabeçalho

Ordem no inspector: pendência contextual → segurança → sistema → execução atual
→ conexões MCP → orçamento. Listas resumidas a até quatro itens, com `Ver todos`.
Em Chat, execução atual acompanha o turno selecionado; em outras telas acompanha
o recurso inspecionado. Um cabeçalho identifica esse contexto.

O inspector permite navegar para detalhes, não mudar políticas, reiniciar
containers nem restaurar arquivos. Mutações ficam no centro, com contexto e
permissão. Atalhos operacionais ficam no Início ou no resultado da tarefa.

No cabeçalho, estados independentes: `Núcleo conectado · WSL`,
`Voz desligada / Aguardando “Zordon” / Ouvindo comando / Indisponível` e
notificações. O status da voz tem acesso explícito a desligar captura. `wake`
significa captura ativa para palavra de ativação; não pode parecer microfone
desligado. `open` mostra captura contínua e prazo retornado pelo serviço.

`Pausar ações` aciona Defense Lockdown, o mesmo mecanismo do tray. Até o núcleo
confirmar, mostrar `Solicitando pausa…`; em desconexão, `Pausa não confirmada`.
Nunca prometer que ações pararam apenas porque o botão foi pressionado.

Não repetir hora/data grandes: o Windows já as oferece. Nome do usuário no
rodapé é identificação local, sem estado social “online” ou troca de contas.

## 6. Fluxos obrigatórios

| Fluxo | Sequência observável |
|---|---|
| Consulta UC3 | Enviar → agente/ferramenta em execução → resultado + origem → conclusão + uso → log |
| Ação que exige permissão | Proposta resolvida → diálogo → decisão/expiração → resultado correlacionado |
| Automação UC6 | Intenção → especificação concreta (alvo, gatilho, fuso, efeito, prazo) → revisão → persistência confirmada |
| Incidente UC11/12 | Mensagem determinística → ação/estado → evidências → opções permitidas → confirmação de leitura separada |
| Alteração de projeto | Plano comunicado → escopo, arquivos, risco e orçamento → decisão → etapas → diff/testes/docs → resultado parcial ou completo |
| Estouro de orçamento | Avisos 70% e 90% → 100% interrompe conforme núcleo → parcial preservado + motivo |
| Reconexão | Conectando → replay ou snapshot → estado atualizado; nada é reenviado silenciosamente |

Plano de alteração exibe repositório, branch, arquivos, SPECs, agentes, orçamento,
riscos e reversão. Proposta que toca núcleo de confiança identifica revisão
humana obrigatória; não oferece “instalar automaticamente”. Referência normativa:
[auto-modificação](../../process/self-modification.md).

O diálogo de permissão segue [UI §6](design.md#6-diálogo-de-permissão): resumo,
ferramenta, agente, alvos e reversibilidade derivados do núcleo; análise do LLM
em caixa separada; expiração visível; RED nunca lembrado. Nenhum botão
pré-focado, Enter não confirma, liberação de Permitir após 1,5 s em RED.
Foco inicial no título explicativo, e ativação deliberada por teclado preservada.

## 7. Estados e recuperação

| Estado | O que aparece | O que pode acontecer |
|---|---|---|
| Inicializando | Estado textual de conexão; placeholders sem valores | Sem comandos até conexão e sincronização |
| Núcleo offline | Banner, última sincronização, tentativas; dados antigos rotulados | Histórico já carregado legível; envio bloqueado e rascunho preservado |
| Host offline | Voz e ações Windows indisponíveis | Chat e capacidades WSL disponíveis conforme núcleo |
| Sem provider | Motivo e caminho para configuração | Rascunho preservado; fallback apenas se retornado como disponível |
| MCP indisponível | Motivo, ferramentas afetadas, próxima tentativa se conhecida | Reconexão normal conforme serviço |
| MCP isolado por segurança | Evidências e disjuntor aberto | Sem reconexão automática; liberação somente pelo usuário |
| GPU sem telemetria | `— · Não disponível` | Nunca 0% inferido nem placa da imagem fixada |
| Aguardando permissão | Execução suspensa, decisão e prazo | Não marcar como lentidão do modelo |
| Parcial / cancelado / falhou | Saída preservada e motivo | Retentar só quando permitido; nova tentativa de efeito exige reavaliação |
| Defense Lockdown | Banner vermelho persistente com motivo | Conversa/consulta continuam; mutações bloqueadas; saída explícita do usuário |
| CRITICAL pendente | Banner persistente e incidente acessível | Confirmar leitura não resolve incidente nem libera componente |
| Sem itens | Explicação e ação contextual | “Nenhum MCP configurado” difere de “Falha ao carregar MCPs” |
| Índice RAG desatualizado | Origem/versão e aviso em Conhecimento/Diagnóstico | Não apresentar documento antigo como versão atual |

Métrica obsoleta: mostrar última amostra e marcar `Desatualizada` após três
intervalos esperados da fonte. O intervalo deve vir da configuração de coleta;
disco a 0,1 Hz não é inválido após 3 s. Origem e frescor ainda precisam ser
formalizados no payload; até lá, usar `Atualização desconhecida`.

Na perda da conexão, resultado de ação pendente pode ser **desconhecido**; não
presumir falha e repetir. `resumed:false`, mudança de `startId` ou `gap` exige
ressincronizar as projeções afetadas conforme contrato. Estado antigo fica
identificado até o snapshot terminar.

## 8. Segurança, notificações e privacidade

Defesa e Windows Defender têm indicadores separados. “Sem incidentes pendentes”
não equivale a “computador protegido de todas as ameaças”. Seguir os três anéis
de [defesa](../../security/defense.md).

Permissão, leitura de alerta e decisão sobre incidente são interações diferentes.
UI não calcula risco e não transforma timeout em aprovação. Incidentes usam os
oito campos da [norma de comunicação](../../security/communication.md#4-contrato-de-explicação).
Segredos chegam mascarados e continuam mascarados em copiar/exportar/detalhes.

Notificações são agrupadas por incidente. Regras de interrupção, orçamento e
exceções pertencem à norma: inclusive eventos `change` e classes de integridade
que não podem ser suprimidos. Não criar limitador visual que descarte essas
exceções. Fechar toast não equivale a confirmar CRITICAL.

Fechar a janela recolhe para tray; `Encerrar interface` não encerra núcleo ou host.
Overlay é compacto, acessível e respeita tela cheia e redução de movimento.
Sem cache novo de conversas em disco no desktop nesta proposta: acesso offline
garante apenas o histórico já carregado em memória; persistência local exigirá
contrato e análise próprios.

## 9. Handoff, dependências e validação

As regras de threading/tray/overlay continuam em [design.md](design.md).
Os métodos e eventos são os do [ZWP](../../api/zwp-protocol.md); lacunas explícitas
estão em [design-review.md](design-review.md). Esta proposta não muda transporte,
motor de permissão, persistência ou biblioteca de UI.

Antes de codar, escrever SPECs aprováveis por entrega: shell e navegação (M0/M1),
chat, voz, permissão, recursos e observabilidade nos seus marcos. Não reservar
um ID global para esta visão visual; `SPEC-001` continua previsto para a fundação.

| Critério de revisão | Evidência esperada na futura implementação |
|---|---|
| Layout em 1600×900, 1440×900, 1366×768 e 1024×768 | Capturas e navegação sem cortes de controles |
| Escala e janela reduzida | 100/125/150/200% DPI e texto ampliado; regiões se reorganizam |
| Teclado e Narrator | Percurso completo até enviar, cancelar, negar e inspecionar alvos |
| Permissão | Enter não permite; RED aguarda 1,5 s; expiração nega; alvos acessíveis |
| Offline/replay | Dados rotulados, entrada preservada, nenhuma repetição automática de ação |
| Voz | off/wake/push/open distintos; estado real de captura e host refletido |
| Telemetria | Ausente/obsoleta/origem corretos; sem valores ilustrativos em produção |
| Uso | Estimativas rotuladas; soma por agente sem dupla contagem de filhos |
| Segurança | Lockdown mantém leitura; CRITICAL persistente; leitura não libera disjuntor |
| Desempenho | Streaming/logs virtualizados; UI em lote; repouso sem efeitos contínuos |

A prévia exercita somente navegação ilustrativa, cenários de estado, inspector,
tokens visuais e exemplo de permissão. Não é um cliente ZWP nem uma entrega M1.
