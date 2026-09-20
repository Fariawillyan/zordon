---
document: glossary
module: meta
section: glossary
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [termos,definicoes]
specId: null
---

# Glossário

| Termo | Significado no Zordon |
|---|---|
| **Agente** | Configuração (prompt + escopo de ferramentas + teto de permissão + política de modelo + orçamento) sobre um runtime comum. Não é uma classe. [Agentes](specs/agents/design.md) |
| **Auditoria** | Registro append-only com cadeia de hash de toda ação tentada e seu desfecho. [Segurança §7](security/model.md#7-auditoria) |
| **Contaminação (taint)** | Marca aplicada a um turno que leu conteúdo sensível; eleva o risco de ações de saída de dados. [Segurança §6](security/model.md#6-prompt-injection) |
| **Conter e avisar** | Exceção ao "comunicar antes de agir", sujeita a três condições conjuntivas. Janela máxima de 2 s. [Comunicação §6](security/communication.md#6-comunicar-antes-vs-conter-e-avisar) |
| **Destilação** | Processo assíncrono que transforma um turno em fatos de longo prazo. [Memória §5](specs/memory/design.md#5-destilação) |
| **Effect** | Categoria de consequência de uma ação (`WRITE_FS`, `SPAWN_PROCESS`, `NETWORK`…), usada em políticas independentes de ferramenta. [Interfaces §5](api/core-interfaces.md#5-permissionengine) |
| **Drift de MCP** | Mudança na superfície declarada de um servidor MCP entre conexões. Detector `ai.mcp-drift`. [MCP §3](specs/mcp/design.md#3-toolregistry) |
| **Endpoint file** | `endpoint.json`, escrito pelo núcleo em área visível ao Windows, carregando endereço e token. Resolve descoberta, autenticação e prova de co-localização. [ADR-0006](adr/ADR-0006-descoberta-por-arquivo-de-endpoint.md) |
| **EventBus** | Barramento interno do núcleo; o ZWP é a projeção dele para clientes. [ADR-0011](adr/ADR-0011-event-bus-com-replay.md) |
| **GREEN / YELLOW / RED** | Níveis de risco de uma ação **resolvida**. [Segurança §2](security/model.md#2-classificação-de-risco) |
| **Histerese** | Limiar de disparo diferente do de rearme, para evitar tempestade de alertas. [Automação §4](specs/automation/design.md#4-histerese-e-deduplicação) |
| **Intent Router** | Decide o que fazer e quem faz, com rota rápida determinística antes de qualquer LLM. [Core §2](specs/core/design.md#2-intent-router) |
| **Interop** | Capacidade do WSL de executar binários Windows. Indisponível sob systemd; não usada no caminho principal. [R5](architecture/windows-wsl.md#r5--wsl_interop-não-existe-dentro-de-um-serviço-systemd) |
| **Kill switch** | O mesmo mecanismo do Defense Lockdown, acionado manualmente no tray. [Segurança §8](security/model.md#8-limites-e-desligamento-de-emergência) |
| **Mascaramento** | Segredo exibido só com prefixo e 3 últimos caracteres: `sk-proj-****************92F`. Nunca completo, em lugar nenhum. [Segurança §5](security/model.md#5-segredos) |
| **MCP** | Model Context Protocol. O Zordon é cliente, nunca servidor. [MCP](specs/mcp/design.md) |
| **Modo espelhado** | `networkingMode=mirrored` no WSL: `127.0.0.1` funciona nos dois sentidos. [R3](architecture/windows-wsl.md#r3--o-ip-do-wsl2-muda-a-cada-boot) |
| **Núcleo (Core)** | `zordon-core`, no WSL. Onde vive toda inteligência, estado e decisão. |
| **Pinned tools** | Ferramentas sempre presentes no prompt de um agente, fora da seleção semântica. [Agentes §1](specs/agents/design.md#1-o-que-é-um-agente-aqui) |
| **Procedência** | `turn_id` de origem de um fato de memória. Obrigatório. [Memória §3](specs/memory/design.md#3-schema) |
| **Rota rápida** | Regra determinística que resolve comandos frequentes sem chamar LLM. Só pode mapear ações GREEN. |
| **RRF** | Reciprocal Rank Fusion — funde ranking lexical e vetorial. [Memória §4](specs/memory/design.md#4-recuperação-híbrida) |
| **Seleção semântica** | Escolha das ~12 ferramentas relevantes entre todas as registradas. [ADR-0010](adr/ADR-0010-selecao-semantica-de-tools.md) |
| **Skill** | Capacidade nativa do Zordon, em contraste com ferramenta vinda de MCP. [Interfaces §3](api/core-interfaces.md#3-zordonskill) |
| **Teto de permissão** | Risco máximo que um agente pode sequer solicitar. Delegar nunca eleva. [Agentes §4](specs/agents/design.md#4-delegação) |
| **Turno** | Uma interação completa: entrada do usuário → resposta final, incluindo todas as voltas de ferramenta. |
| **Wake word** | "Zordon". Detectada localmente, sem envio contínuo de áudio. [Voz](specs/voice/design.md) |
| **Windows Bridge** | Contrato no núcleo, implementação no `zordon-host`. Nenhuma string de shell atravessa. [Interfaces §6](api/core-interfaces.md#6-windowsbridge) |
| **ZPath** | Caminho canônico com origem explícita (`WINDOWS` ou `WSL`). Nenhuma API de Skill aceita caminho como `String`. [R8](architecture/windows-wsl.md#r8--caminhos-são-dois-mundos) |
| **Anel 1 / 2 / 3** | Escopo declarado da defesa: superfície de IA (autoridade), comportamento do host (complementar), malware clássico (orquestração). [ADR-0017](adr/ADR-0017-zordon-nao-e-antivirus.md) |
| **Capability** | Concessão explícita a um sujeito: efeitos, escopo, prazo. Capacidade sem prazo é rejeitada. [Defesa §2](security/defense.md#2-zero-trust-aplicado) |
| **Circuit breaker** | Contenção por sujeito (agente, MCP, ferramenta, processo). Abre sozinho, fecha só com o usuário. [ADR-0018](adr/ADR-0018-circuit-breaker-e-lockdown.md) |
| **Defense Lockdown** | Modo de contenção do sistema: tudo somente-leitura, monitoramento e notificação ativos. Só o usuário sai. [Defesa §8](security/defense.md#8-defense-lockdown) |
| **Detector** | Plugin determinístico que observa uma fonte e emite `Signal`. Nunca decide severidade sozinho. [Defesa §4](security/defense.md#4-detection-engine) |
| **Finding** | Resultado da correlação de sinais: severidade, evidências e `rationale` gerado por código. |
| **Invariante** | Propriedade do produto que não admite exceção pontual. São quatro. [docs/README](README.md#as-cinco-invariantes) |
| **Nível de alerta** | 🟢 INFO, 🟡 WARNING, 🟠 HIGH, 🔴 CRITICAL. Define canais e se interrompe o usuário. [Comunicação §3](security/communication.md#3-níveis-de-alerta) |
| **NotificationCenter** | Componente único por onde passa toda comunicação iniciada pelo Zordon. [Comunicação §2](security/communication.md#2-zordonnotificationcenter) |
| **Quarantine Vault** | Cofre onde arquivos suspeitos são **movidos**, nunca apagados. Com hash, origem, evidências e restauração. [Defesa §6](security/defense.md#6-quarantine-vault) |
| **`rationale`** | Campo obrigatório de um `Finding` que responde "por que isso foi considerado suspeito". Determinístico. |
| **SecurityEvent** | Registro imutável de toda iniciativa de defesa, com `userMessageId` ligando à notificação entregue. [Defesa §11](security/defense.md#11-securityevent) |
| **SecurityPolicy** | Arquivo lido na inicialização e imutável em execução. Nenhum componente do Zordon o escreve. |
| **Signal** | Observação de um detector, com peso. Vira `Finding` só após correlação. |
| **`userMessageId`** | Liga um `SecurityEvent` à notificação que o usuário recebeu. Nulo com ação executada = bug de severidade máxima. |
| **Acceptance Criteria (`CA-n`)** | Critério verificável de uma SPEC, ligado a teste por `@AcceptanceCriteria`. [SDD §6](process/spec-driven-development.md#6-specs-executáveis) |
| **AgentProfile** | `ASSISTANT` (serve o usuário) ou `ENGINEERING` (constrói o Zordon). Mesmo mecanismo, definições diferentes. [ADR-0021](adr/ADR-0021-dois-perfis-de-agente.md) |
| **AgentRegistry** | Registro de agentes; cada um declara capacidades, ferramentas, caminhos, escopo de segurança e orçamento. [Catálogo §2](agents/catalog.md#2-agentregistry) |
| **Chunk** | Unidade indexada do RAG: uma seção de documento, com metadados herdados do front-matter. [Indexação §2](rag/indexing.md#2-chunking) |
| **ContextRouter** | Entrega a cada agente só o contexto relevante, dentro do escopo e do orçamento. [Context Router](rag/context-router.md) |
| **Definition of Done** | Os dez portões que definem "pronto". Compilar não é um deles, e documentação não é opcional. [DoD](process/definition-of-done.md) |
| **Front-matter** | Bloco YAML no topo de todo documento com os metadados do RAG. Ausência reprova o build. [ADR-0023](adr/ADR-0023-documentacao-como-fonte-de-verdade.md) |
| **Golden test** | Tabela versionada que reprova mudança de classificação, detector ou formato sem revisão explícita. [Testes §6](testing/strategy.md#6-golden-tests) |
| **Least privilege** | Nenhum agente tem acesso a tudo: ferramentas, caminhos, documentos e efeitos são declarados. [Catálogo §3](agents/catalog.md#3-least-privilege-na-prática) |
| **RAG** | Recuperação sobre a própria documentação. Conhecimento, nunca autoridade. [ADR-0020](adr/ADR-0020-rag-como-conhecimento.md) |
| **`RAG_STALE`** | Documento alterado e ainda não reindexado. Bloqueia a Definition of Done. |
| **SPEC** | Especificação que precede a implementação, com critérios de aceite que viram testes. [SDD](process/spec-driven-development.md) |
| **Spec-Driven Development** | `IDEIA → SPEC → REVIEW → PLANO → IMPLEMENTAÇÃO → TESTES → VALIDAÇÃO → DOCUMENTAÇÃO`. [ADR-0019](adr/ADR-0019-spec-driven-development.md) |
| **TokenUsageService** | Registro de consumo por tarefa, agente, modelo e provider, com `accuracy` explícito. [Tokens](operations/token-usage.md) |
| **`accuracy` (EXACT \| ESTIMATED)** | Marca se a contagem de tokens veio da API ou de estimativa. Estimativa nunca é exibida como exata. |
| **`ApprovedPlan`** | Prova tipada de que o preflight rodou e o usuário foi comunicado. `ChangeExecutor` só aceita este tipo. [Auto-modificação §11](process/self-modification.md#11-interfaces) |
| **`ChangePlan`** | Plano de alteração de projeto: arquivos, SPECs, impacto, agentes, orçamento, risco. `humanSummary` gerado por código. |
| **`MODIFY_SELF` / `MODIFY_PROJECT` / `MODIFY_TRUST_KERNEL`** | Efeitos de alteração de código. O terceiro é sempre RED e nunca aplicado — só proposto via PR. |
| **Núcleo de confiança** | Caminhos cuja integridade sustenta as invariantes. O Zordon propõe, nunca aplica. [Auto-modificação §4](process/self-modification.md#4-núcleo-de-confiança) |
| **Preflight** | Os nove passos obrigatórios antes de alterar qualquer projeto. [Auto-modificação §6](process/self-modification.md#6-preflight-obrigatório) |
| **Zero Trust** | Nada confiável por posição; identidade, capacidades e hierarquia imutável. [Defesa §2](security/defense.md#2-zero-trust-aplicado) |
| **Origem** | De onde veio a ordem (`ui`, `voice`, `automation`, `agent`, `autonomous`). Limita o risco máximo da ação. [ADR-0030](adr/ADR-0030-origem-da-ordem.md) · [Identidade](security/identity.md) |
| **Sandbox** | Worktree git + bubblewrap + `systemd-run`, com rede desligada, onde roda código de agente. [ADR-0031](adr/ADR-0031-sandbox-para-codigo-de-agente.md) · [Sandbox](security/sandbox.md) |
| **SecretBroker** | Usa o segredo em nome de quem pede, sem entregá-lo. [ADR-0032](adr/ADR-0032-segredo-se-usa-nao-se-entrega.md) · [Segurança](security/model.md#uso-intermediado) |
| **Capability Registry** | Vocabulário fechado de capacidades (`TEXT`, `CODE`, `VISION`, `LOCAL_ONLY`…) declaradas por provider. [Capacidades](specs/core/capabilities-and-routing.md) |
| **Model Router** | Escolhe o modelo por restrições duras, privacidade e orçamento. [ADR-0033](adr/ADR-0033-capacidades-e-roteador-de-modelos.md) |
| **ExtensionRegistry** | Registro das extensões com manifesto versionado e `approvedHash`. [ADR-0034](adr/ADR-0034-manifesto-de-extensao-versionado.md) · [Extensões](architecture/extensions.md) |
| **Planner** | Transforma um pedido de vários passos em `Plan` persistido. [Planner](specs/agents/planner.md) |
| **TaskStore** | Estado durável de planos e tarefas; sobrevive à queda do WSL. [ADR-0035](adr/ADR-0035-planos-duraveis-e-estado-de-tarefas.md) |
| **Verifier** | Confere o critério de conclusão de uma tarefa com evidência, antes de dizer "pronto". [ADR-0036](adr/ADR-0036-conclusao-verificada.md) |
| **Evaluation Engine** | Mede uma capacidade contra um conjunto fixo em `evals/`. [Avaliação](specs/agents/evaluation.md) |
| **KnowledgeGraph** | Relações entre entidades na memória, com fonte obrigatória. [ADR-0037](adr/ADR-0037-relacoes-na-memoria.md) · [Memória §10](specs/memory/design.md#10-relações-grafo-de-conhecimento) |
| **ZWP** | Zordon Wire Protocol: JSON-RPC 2.0 bidirecional sobre WebSocket + frames binários. [ZWP](api/zwp-protocol.md) |
