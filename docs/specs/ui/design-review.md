---
document: ui-design-review
module: ui
section: design-review
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [design,referencias,contratos,pendencias,rastreabilidade]
specId: null
---

# Fundamentos e pendências do design inicial

## 1. Objetivo e base documental

Registrar como a documentação orienta o [design system](design-system.md) e o
[layout desktop](desktop-layout.md), sem fazer uma proposta visual parecer uma
capacidade já implementada. Revisão de design de 17/09/2026, status **DRAFT**.

O acervo inicial contém 73 arquivos Markdown: três na raiz e 70 em `docs/`,
incluindo os 24 ADRs. Também foram considerados CODEOWNERS e NOTICE. A análise
cruza visão, roadmap, arquitetura, contratos, segurança, agentes, RAG, operação,
processo, testes e todos os designs de módulo com a imagem fornecida pelo usuário.

| Fonte | Consequência para o design |
|---|---|
| [Visão](../../vision.md), [roadmap](../../roadmap.md), [índice](../../README.md), README da raiz | Assistente residente com ação, transparência e entregas progressivas M0–M8 |
| [Arquitetura](../../architecture/overview.md), [componentes](../../architecture/components.md), [comunicação](../../architecture/communication.md), [Windows/WSL](../../architecture/windows-wsl.md) | UI descartável; separar núcleo, host, voz e ambiente das métricas |
| [Eventos](../../architecture/event-driven.md), [ZWP](../../api/zwp-protocol.md), [interfaces](../../api/core-interfaces.md) | Streaming, snapshots, reconexão, cancelamento e sem execução direta de ferramentas pela UI |
| [Modelo de segurança](../../security/model.md), [defesa](../../security/defense.md), [comunicação](../../security/communication.md), [mapa](../../architecture/security.md) | Permissões concretas, incidentes determinísticos, leitura distinta de decisão, lockdown e estado do Defender |
| [Core](../core/design.md), [voz](../voice/design.md) | Provider/fallback visíveis; captura local e modos distintos; texto continua útil sem voz |
| [MCP](../mcp/design.md), [automação](../automation/design.md) | Catálogos e estados reais; gatilhos revisáveis, coleta por fonte e sem “todos online” fictício |
| [Memória](../memory/design.md), [RAG](../../rag/README.md), [base](../../rag/knowledge-base.md), [indexação](../../rag/indexing.md), [router](../../rag/context-router.md) | Memória separada de conhecimento; procedência, escopo, versão e índice desatualizado visíveis |
| [Agentes](../../agents/README.md), [catálogo](../../agents/catalog.md), [orquestração](../../agents/orchestration.md), [runtime](../agents/design.md) | Perfis Assistente/Engenharia; tarefas, filhos, etapas e orçamento identificados |
| [Observabilidade](../../operations/observability.md), [tokens](../../operations/token-usage.md), [instalação](../../operations/install.md) | Uso e Diagnóstico como destinos; custo exato/estimado; saúde por subsistema |
| [SDD](../../process/spec-driven-development.md), [template](../../process/spec-template.md), [DoD](../../process/definition-of-done.md), [padrões](../../process/code-standards.md), [rastreabilidade](../../process/traceability.md), [auto-modificação](../../process/self-modification.md) | Baseline DRAFT; SPEC antes de produto; critérios verificáveis e plano antes de modificar projeto |
| [Testes](../../testing/strategy.md), [supply chain](../../security/supply-chain.md), CONTRIBUTING, SECURITY, CODEOWNERS e NOTICE | Validação nativa, dependências/fontes revisadas e nenhuma nova stack obrigatória |
| [ADRs 0001–0024](../../adr/README.md), [glossário](../../glossary.md), [índice de SPECs](../README.md) e READMEs de segurança/defesa/RAG | Preservar decisões aceitas, terminologia e fonte única de cada norma |

## 2. O que muda em relação à imagem

| Referência ilustrativa | Recomendação |
|---|---|
| Holograma grande e slogan permanente | Marca compacta; arte opcional só na entrada inicial |
| Glow em textos, bordas e botões | Ciano concentrado em foco, seleção e marca |
| Início selecionado contendo Chat completo | Destinos distintos; a conversa ilustrada pertence a Chat |
| Todo agente “Online” | Disponibilidade separada de execução e de espera por permissão |
| Medidores circulares de CPU/RAM/GPU/disco | Valores, unidades, barras e origem; detalhes em Sistema |
| “Sistema WSL” com informação de GPU/host misturada | Rotular a origem de cada fonte e tratar indisponibilidade |
| Reiniciar todos os containers junto à consulta | Fluxo de ação com alvos e permissão; leitura primeiro |
| “Limpar” conversa | Nova conversa, preservando histórico e auditoria |
| Sem Uso, Segurança e Diagnóstico evidentes | Destinos explícitos e pendências visíveis no cabeçalho |
| “API local” como resumo geral | Separar transporte local, áudio local e provider de IA local/remoto |

## 3. Cobertura dos contratos

“Documentado” abaixo significa previsto no acervo, não implementado. Nenhum
método novo é introduzido por este documento.

| Área | Contrato documentado | Lacuna antes da implementação |
|---|---|---|
| Conexão | `session.hello`, `system.health`, replay, `startId`, `seq` | Estado completo de inicialização e payload de cada subsistema |
| Chat | `chat.send/history/newSession/cancel`, `AI_RESPONSE`, `AI_ERROR` | Seleção explícita de agente não aparece nos params de `chat.send`; título/renomeação e paginação de sessões |
| Resultados | `TOOL_STARTED`, `TOOL_FINISHED` com `summary` | Payload tipado para tabela Docker e referências de origem; não inventar dados a partir de resumo |
| Anexos | `attachments?`, frame `FILE_CHUNK` | Schema, limites, validação, destino e ciclo de upload |
| Voz | Modos e `VOICE_*`/`TTS_*` | Snapshot completo, confirmação de captura off, seleção de dispositivo, calibração e prazo do modo open |
| Permissão | `ui.requestPermission`, `permission.respond` | Escolher um caminho canônico de resposta; deduplicar por `requestId` e definir deadline absoluto |
| Agentes | `agent.list/run/cancel`, `AGENT_*` | Snapshot de execuções, relações pai/filho e lista por perfil tipados |
| MCP / Skills | `mcp.list/connect/disconnect`, `skill.list`, `tool.describe` | CRUD de configuração e distinção tipada entre backoff de transporte e isolamento de segurança |
| Memória | `memory.search/forget` | Correção, exportação, esquecimento por assunto e sessão efêmera ainda sem métodos detalhados |
| Automações | Métodos list/create/delete/pause citados | Schemas de gatilho, revisão, fuso, próxima execução e erro |
| Métricas | `system.metrics`, `SYSTEM_METRICS` | Origem por fonte, timestamp de coleta, unidade e intervalo esperado |
| Uso | `TokenUsageService`, `TOKEN_USAGE`, avisos de orçamento | Métodos de consulta/exportação, tópico e snapshot/replay de uso não definidos no catálogo ZWP |
| Conhecimento | Interfaces e eventos de RAG em docs próprios | Métodos ZWP para fontes, busca, versão e reindexação; não inventar `rag.*` como API pronta |
| Logs / notificações | Eventos, `security.incidents`, `security.acknowledge` | Histórico paginado genérico, leitura de detalhes e exportação; lista durável de notificações gerais |
| Alterações de projeto | `change.plan/approve/revert`, `CHANGE_*` | Schema visual completo de plano, diff e evidências de testes |
| Preferências | Parte de `permission.policy.*` | Persistência de preferências do desktop; `SecurityPolicy` permanece somente leitura |

Contrato ausente significa controle sem disponibilidade no produto até a SPEC
correspondente. A prévia pode mostrar a intenção, sempre identificada como tal.

## 4. Divergências encontradas que afetam a UI

| Divergência | Encaminhamento |
|---|---|
| UI §10 afirmava contraste suficiente de `#5C6779` | Corrigido no design com medição e token legível |
| UI antiga não listava Uso/Conhecimento e omitia Segurança no wireframe | Novo mapa abrange os domínios e distingue suas fases |
| Visão §3 fala em enfileirar offline; UI §9 bloqueia entrada | Baseline preserva rascunho e bloqueia envio; fila de execução exigiria SPEC própria, idempotência e intenção explícita |
| UI §6 limitava interrupções a permissão e CRITICAL | Remissão à norma completa, que também protege eventos `change` e exceções de integridade |
| UI §3 limitava animação contínua, tray tinha vermelho pulsante em lockdown | Lockdown permanece vermelho estático; presença persistente e texto, sem depender de pulsação |
| ADR-0011/event-driven usam `REJECT_PUBLISH`; tabela ZWP fala em fila sem limite prático | Resolver capacidade/backpressure na SPEC de transporte; UI não descarta eventos de segurança/decisão |
| MCP descreve disjuntor temporário; segurança exige liberação humana | Separar falha de transporte de `ZordonSafetyCircuitBreaker`; isolamento de segurança nunca reconecta sozinho |
| Catálogos de eventos não detalham Uso/RAG e dados de resultado | Formalizar antes de ligar os componentes, conforme §3 |
| Roadmap abre com M0–M7, mas também contém M8 | Layout considera M8 existente no corpo; texto introdutório merece revisão futura |

As divergências normativas e os contratos ficam registrados para as SPECs dos
módulos; nenhum ADR aceito ou documento de segurança foi alterado nesta entrega.

## 5. Entrega e revisão

Entregues: baseline visual, composição desktop, matriz de estados, mapeamento
de contratos e prévia local. Sem implementação do cliente JavaFX, sem conexão ZWP
e sem dados reais. Revisão humana e futuras SPECs ainda não ocorreram.

O diretório recebido não contém `.git`, Gradle, testes de produto ou runtime RAG.
Não há commit, build JavaFX, reindexação ou registro real em `TokenUsageService`
a declarar. Documentação DRAFT não deve ser indexada como conhecimento revisado
antes de passar pelo processo do projeto.

Para revisar visualmente, abrir `preview/index.html` no navegador e alternar
os cenários. HTML/CSS/JS são artefatos de documentação, não mudança de stack.
Fontes externas, analytics, acesso ao microfone e requisições ao núcleo não
fazem parte da prévia.
