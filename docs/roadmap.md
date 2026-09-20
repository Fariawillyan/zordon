---
document: roadmap
module: product
section: roadmap
version: 1
updatedAt: 2026-09-19
securityLevel: public
tags: [marcos,entregas,riscos]
specId: null
---

# Roadmap

Oito marcos. Cada um termina com algo demonstrável e com critérios de pronto
verificáveis. A ordem é por dependência técnica e por risco: o que pode reprovar
o projeto inteiro vem cedo.

## M0 — Fundação

**Objetivo:** o esqueleto que torna todo o resto possível, e a prova de que a
parte mais arriscada funciona.

```text
repositório Git + estrutura Gradle multi-módulo
LICENSE (Apache 2.0), NOTICE, SECURITY.md, CONTRIBUTING.md, CODEOWNERS
SPEC-001 (o processo validando a si mesmo) + validação de front-matter no build
TraceabilityIndex: @Spec, @AcceptanceCriteria, verificação de órfãos
branch protection + pipeline de PR (CI, SAST, deps, secrets, SBOM, licenças)
zordon-api com os records do ZWP
teste arquitetural (ArchUnit) com as 5 regras de 05 §5.4
CI: build, teste, spotless
esqueleto de zordon-core que sobe, serve ZWP e responde session.hello
esqueleto de zordon-desktop que conecta e mostra "CORE ONLINE"
unit systemd + tarefa agendada + arquivo de endpoint
```

**Pronto quando:** reiniciar o Windows, não abrir nenhum terminal, abrir o
`zordon-desktop` e ver "CORE ONLINE" sem nenhuma ação manual.

**Estado (2026-09-18): pronto.** Verificado reiniciando o Windows: logon às
11:18:51, núcleo pronto às 11:19:19 sem nenhum terminal aberto, de pé sem
interrupção, e o desktop conectou mostrando CORE ONLINE. A primeira tentativa
reprovou — o WSL desligava a distro 15 s depois do boot —, e a correção
(`instanceIdleTimeout=-1`) está em [Windows↔WSL R1](architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows).

Este marco existe para atacar [R1](architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows)
antes de qualquer investimento em IA. Se o núcleo não sobrevive a um reboot, nada
mais importa — e é melhor descobrir isso na semana 1.

## M1 — Chat funcional

```text
AiProvider + adaptador Anthropic (streaming, ferramentas, cache)
adaptador compatível com OpenAI + providers por configuração (SPEC-004)
IntentRouter (só rota rápida + agente geral)
TurnManager e composição de contexto
EventBus com seq e replay
UI de chat com streaming de tokens
System tray com estados
Reconexão automática com backoff
```

**Pronto quando:** conversa de texto com streaming; fechar a janela, reabrir e o
histórico continuar; matar o núcleo e a UI mostrar offline, reconectando sozinha
quando ele voltar; um turno completo aparecer na tela de Logs.

**Estado (2026-09-18):** implementado e testado ponta a ponta com provider falso
e servidor falso. **Falta a resposta de um modelo real**, por decisão: o projeto
não vai usar API paga por crédito, e o provider por assinatura (`claude`/`codex`)
será definido no M3 ([ADR-0026](adr/ADR-0026-provider-agnostico.md)). Até lá, o
chat responde pelas rotas locais e diz com clareza que não há modelo disponível.

**Dívida de interface:** a janela entregue tem Chat e Logs em abas. O shell do
[layout](specs/ui/desktop-layout.md) — navegação, cabeçalho de estados,
inspector, Início e Diagnóstico mínimo — não entrou; passa a ser a primeira
entrega do M2.

## M2 — Voz

```text
shell do desktop conforme o layout: navegação, cabeçalho de estados,
  inspector, Início e Diagnóstico mínimo; fontes empacotadas (dívida do M1)
tela de Voz
zordon-host: captura, reprodução, autostart, supervisor do WSL
sidecar zordon-voice: VAD, wake word, STT, TTS
frames binários ZWP com controle de fluxo
máquina de estados de voz + modos
overlay
normalização PT-BR para TTS
```

**Pronto quando:** com a janela fechada, "Zordon, que horas são?" é respondido
por voz em menos de 2,5 s (p50); o falso positivo de wake word fica abaixo de 1
por 8 h de fala ambiente; `voice.setMode off` realmente desliga o microfone (LED
do Windows apaga).

Este é o **MVP** segundo [Visão §7](vision.md#7-critério-de-sucesso-do-mvp).

**Estado (2026-09-18):** em andamento. Entregue o shell do desktop
([SPEC-005](specs/ui/SPEC-005-shell-do-desktop.md)): quatro faixas de largura,
destinos futuros com o marco, cabeçalho e inspector com estado real, conversa
virtualizada, Diagnóstico via `system.diagnostics` e fontes empacotadas. Entregue
também a tela de Voz com o estado da voz
([SPEC-006](specs/voice/SPEC-006-tela-e-estado-da-voz.md)): modos, captura
confirmada pelo host, dispositivo e o núcleo pedindo a clientes pelo ZWP. Entregue
o host do Windows ([SPEC-007](specs/host/SPEC-007-host-do-windows.md)): conectado ao
núcleo do serviço, captura confirmada desligada, microfones reais listados, e o
supervisor do WSL pelo agendador ([ADR-0027](adr/ADR-0027-supervisor-do-wsl-pelo-agendador.md)).
O desktop passou a rodar no Windows, onde os efeitos sonoros tocam comprovadamente
([SPEC-008](specs/voice/SPEC-008-console-visual-e-efeitos-sonoros.md), falta o aceite
auditivo). Os frames binários de áudio com crédito e o teste do microfone estão
implementados e testados no microfone real
([SPEC-009](specs/voice/SPEC-009-frames-de-audio-e-teste-do-microfone.md)). O Zordon passou a ser voice-first
([ADR-0029](adr/ADR-0029-voice-first.md), [SPEC-012](specs/voice/SPEC-012-voice-first-narracao-e-estados.md)):
intérprete de atividade, narrador, dez estados visuais, modo técnico e Live Trace.
O motor de voz ([SPEC-011](specs/voice/SPEC-011-motor-de-voz-ouvir-e-falar.md))
ouve e fala: "que horas são?" respondido por voz em ≈ 2,2 s, só com CPU, com
disparo por clique. A palavra de ativação "Zordon" (etapa 2) está especificada
([SPEC-013](specs/voice/SPEC-013-palavra-de-ativacao-e-conversa-sem-clique.md), em implementação):
detector próprio treinado com dados de licença permissiva
([ADR-0038](adr/ADR-0038-palavra-de-ativacao-treinada-aqui.md)), porque o Whisper como
porteiro foi medido e não serve. Faltam também o overlay e a normalização completa.

## M3 — Ação, com segurança primeiro

**Ordem interna obrigatória** (de [Segurança §9](security/model.md#10-ordem-de-implementação)):

```text
1. AuditLog com cadeia de hash
2. CommandValidator (política de caminhos, lista de programas, sem shell)
3. PermissionEngine + tabela golden de classificação
4. ui.requestPermission com o contrato de exibição de 14 §14.6
5. NotificationCenter com fila durável e entrega garantida
6. Defense Lockdown / kill switch no tray
7. ─── só agora ───
8. WindowsBridge (contrato + implementação no host)
9. ToolRegistry + SkillRuntime
   telas: Skills, Sistema (mínimo) e Segurança (mínimo)
10. Skills GREEN (leitura, métricas, abrir app do catálogo)
11. Skills YELLOW (escrever arquivo, build, Git)
12. Skills RED (quarentena, suspender processo) + Quarantine Vault
```

Junto com os passos 1 a 3 entram as fundações que eles pressupõem:
[origem da ordem](security/identity.md) (ADR-0030), [SecretBroker](security/model.md#uso-intermediado)
(ADR-0032), [Capability Registry e Model Router](specs/core/capabilities-and-routing.md)
(ADR-0033) e o [manifesto de extensão](architecture/extensions.md) (ADR-0034). A
[sandbox](security/sandbox.md) (ADR-0031) entra antes da primeira Skill YELLOW
que roda código.

**Pronto quando:** "Zordon, abra o IntelliJ" funciona; "Zordon, apague os logs do
projeto" é **recusado** e o Zordon oferece a quarentena no lugar; o diálogo RED
lista os 43 arquivos, diz que é reversível e nega ao expirar; a cadeia de
auditoria verifica; ArchUnit reprova qualquer `ProcessBuilder` fora de
`zordon-security` e qualquer `Files.delete` fora do cofre.

O `NotificationCenter` vem **antes** da primeira Skill com efeito colateral. A
invariante de [Comunicação §1](security/communication.md#1-o-princípio-fundamental)
não admite período de transição — nem em desenvolvimento, porque é aí que o
hábito se forma.

**Estado (2026-09-19):** implementado e testado, com aprovação delegada (o owner
ausente pediu que os marcos avançassem sem ele; revisão humana pendente). Os
passos 1 a 6 e 8 a 12 estão nas SPECs
[014](specs/security/SPEC-014-auditoria-validador-e-motor-de-permissao.md) (auditoria
com cadeia SHA-256, validador sem shell, motor com 48 casos golden),
[015](specs/security/SPEC-015-pedido-de-permissao-notificacoes-e-kill-switch.md)
(diálogo com Negar padrão, fila durável de avisos, kill switch no tray e nos
ajustes), [016](specs/security/SPEC-016-execucao-mediada-ferramentas-e-ponte-windows.md)
(`Gatekeeper`, único `ProcessBuilder`, "abra o IntelliJ" pelo catálogo do Menu
Iniciar) e [017](specs/security/SPEC-017-cofre-de-quarentena.md) ("apague os logs" é
recusado e vira quarentena reversível). O provider por assinatura
([SPEC-018](specs/core/SPEC-018-provider-por-assinatura-claude-cli.md)) roda o
`claude` CLI pelo mesmo caminho auditado. Ficaram para depois, com motivo: o
`SecretBroker` completo e a sandbox (nenhuma Skill ainda roda código de
terceiros), e o teste com o owner nas telas reais.

## M4 — MCP

```text
McpManager, transporte stdio e http
descoberta de tools, resources e prompts
ToolRegistry unificado com prefixos
seleção semântica em dois estágios + tools.search
isolamento de falhas (timeout, disjuntor, teto de resposta)
UI de MCP
```

**Pronto quando:** conectar o MCP de Docker por configuração e "quais containers
estão rodando?" funcionar sem código novo; matar o servidor MCP no meio de um
turno e o turno terminar com erro tratado, não com trava; o prompt conter 12
ferramentas e não 80.

**Estado (2026-09-19):** implementado e testado, com aprovação delegada. O modelo
chama ferramentas no laço do turno
([SPEC-019](specs/core/SPEC-019-ferramentas-pelo-modelo.md)): no máximo 12
oferecidas, 25 chamadas e 15 voltas, resultado tratado como dado. O cliente MCP
([SPEC-020](specs/mcp/SPEC-020-cliente-mcp.md)) conecta por stdio sem atrasar o
núcleo, decide o risco pelo piso da configuração, bloqueia servidor que mudou de
superfície até a aprovação na tela, isola falhas com prazo e disjuntor e
reconecta com espera. A tela de ajustes lista os servidores. Servidor que morre
no meio da chamada termina a chamada com erro tratado (testado). Ficaram para
depois: transporte HTTP, `resources` e `prompts`, seleção semântica (M5) e o
teste com um servidor de Docker real, cuja escolha é do owner.

## M5 — Agentes e memória

```text
Agent como configuração TOML
AgentOrchestrator com orçamentos e delegação
os 6 agentes iniciais
MemoryStore: SQLite, FTS5, sqlite-vec
recuperação híbrida com RRF
destilação assíncrona
Planner + TaskStore durável (ADR-0035)
Verifier: conclusão só com evidência (ADR-0036)
UI de agentes e de memória
```

**Pronto quando:** UC4 ("veja por que minha API caiu") percorre múltiplos passos
respeitando o orçamento; UC5 ("abra o projeto que trabalhamos ontem") recupera o
contexto certo ou pergunta; criar um agente novo é criar um arquivo TOML.

**Estado (2026-09-19):** implementado, testado e instalado no serviço, com
aprovação delegada.
- **Memória** ([SPEC-021](specs/memory/SPEC-021-memoria-de-longo-prazo.md)):
  - `zordon-memory` com SQLite, FTS5, RRF e migrações com cópia;
  - "lembre que…" grava; o trabalho num projeto vira evento do dia;
  - a destilação roda depois da resposta;
  - a tela lista e esquece.
- **Agentes** ([SPEC-022](specs/agents/SPEC-022-agentes-como-configuracao.md)):
  - cinco agentes em TOML, e um arquivo novo vira agente sem reiniciar;
  - teto como trava do motor, orçamento, delegação com teto mínimo e disjuntor
    por execução;
  - UC4 com `docker ps` e `docker logs`.
- **Planos** ([SPEC-023](specs/agents/SPEC-023-planos-duraveis-e-verificacao.md)):
  - planos duráveis no mesmo banco;
  - Verifier por ferramenta, julgamento ou tela;
  - retomada só com decisão do usuário.

UC5 funciona pelo contexto (o modelo recebe "trabalhou no projeto X ontem"),
mas o host ainda não abre uma pasta de projeto no IntelliJ. Ficaram para
depois: embeddings locais e `sqlite-vec` (M8), a classificação do agente por
modelo, e as telas próprias de agentes e memória (hoje são seções nos
ajustes).

## M6 — Automação e monitoramento

```text
Scheduler, EventWatcher, ConditionWatcher, WorkflowEngine
execução durável sobre o TaskStore: pausa, retomada, retry, idempotência
zordon-monitor com fontes push (Docker events, inotify)
histerese, deduplicação, janela de silêncio
fila durável de notificações
tela de Diagnostics completa
telas de Automações e Sistema completo
```

**Pronto quando:** UC6, UC7 e UC9 funcionam; um container caindo às 3h com tudo
fechado gera notificação; o consumo de CPU em repouso com escuta ativa fica
abaixo de 3%.

**Estado (2026-09-19):** implementado, testado e instalado, com aprovação
delegada.
- **Monitor** ([SPEC-024](specs/automation/SPEC-024-monitor-do-sistema.md)):
  amostrador de `/proc` com frequência que se adapta (0,2 Hz em repouso, 1 Hz
  quando alguém precisa) e `docker events` por push, pelo caminho auditado —
  ligado no serviço real.
- **Automações** ([SPEC-025](specs/automation/SPEC-025-automacoes.md)): gatilhos
  de intervalo, cron, evento e condição (com histerese, duração mínima e janela
  de silêncio); workflow declarativo com `when`, interpolação, `retry` e
  `onError`; execução durável sobre o `TaskStore`, com a definição do disparo
  gravada junto; aprovação obrigatória na tela antes de existir; só GREEN roda;
  20 falhas desativam; teto diário de tokens que sobrevive ao reinício.
- **Aviso com a tela fechada:** fila durável mais `windows.notify` pelo host,
  com avisos iguais agrupados.
- **CPU:** núcleo em repouso 0,80% de um núcleo; o detector da palavra custa
  1,7% em escuta contínua — ≈ 2,5% somados, abaixo dos 3%.

UC6, UC7 e UC9 estão cobertos por teste (intervalo com `http.check`, evento de
ferramenta e `CONTAINER_EVENT` com aviso). O teste de campo — aprovar a primeira
automação de verdade e ver a notificação nativa às 3h — é do owner. Ficaram para
depois: `inotify` e Git como gatilho, GPU, e ferramentas YELLOW em automação.

## M7 — Defesa

```text
zordon-defense: DetectionEngine + detectores do Anel 1 (superfície de IA)
detectores do Anel 2 (comportamento do host)
correlação de sinais em incidente
DefenseEngine + playbooks de resposta reversível
ZordonSafetyCircuitBreaker por sujeito
Quarantine Vault completo (evidências, restauração, retenção)
integração com Windows Defender / telemetria via zordon-host
defesa de rede local (brute force, varredura, pico de tráfego)
tela de Segurança
anti-fadiga: correlação, dedup, resumo diário, linha de base
```

Ordem interna: **Anel 1 primeiro**. É onde o Zordon é a autoridade e onde o risco
é maior ([ADR-0017](adr/ADR-0017-zordon-nao-e-antivirus.md)). O Anel 2 depende da
linha de base de 7 dias, e o Anel 3 é integração.

**Pronto quando:** UC11 (processo lê `~/.ssh`) é detectado, contido e notificado
em menos de 2 s, com o processo suspenso e nada apagado; UC12 (MCP muda de
superfície) abre disjuntor; UC14 (brute force) bloqueia a origem com prazo
visível e reversível; `integrity.audit-chain` adulterada entra em lockdown
automático; e — o teste que mais importa — **nenhum `SecurityEvent` com ação
executada tem `userMessageId` nulo**.

**Estado (2026-09-19):** detecção e resposta implementadas e testadas, com
aprovação delegada.
- **Detecção** ([SPEC-026](specs/defense/SPEC-026-deteccao-e-correlacao.md)):
  módulo `zordon-defense` com os detectores do Anel 1 (injeção, capacidade não
  declarada, sondagem de permissão, exfiltração, laço, política, drift de MCP,
  segredo na saída) e do Anel 3 (cadeia de auditoria, arquivos instalados e
  configuração), correlação por sujeito em janela de 60 s e motivo escrito por
  código. Achados ficam no `zordon.db` e na tela.
- **Resposta** ([SPEC-027](specs/defense/SPEC-027-resposta-e-disjuntor.md)):
  playbooks reversíveis (isolar MCP, abrir disjuntor, cancelar agente,
  lockdown), disjuntor por sujeito que **só o usuário fecha**, e `SecurityEvent`
  append-only em cadeia de hash — com a invariante do `userMessageId` provada
  por teste.
- **Anel 2 sem root:** credencial aberta por processo desconhecido
  (`/proc/*/fd`), arquivo de persistência alterado e porta nova em LISTEN.

O que **não** foi feito, com motivo: `fanotify`/`auditd` (precisam de root, e
isso muda o modelo de ameaça — merece ADR); ETW e Defender (dependem do canal de
telemetria do host); força bruta, varredura de portas e pico de tráfego
(dependem de captura de rede ou de linha de base); suspensão de processo existe,
mas fica desligada até o owner decidir. UC11 é detectado e notificado; conter
suspendendo o processo é a parte que espera essa decisão.

## M8 — Plataforma de desenvolvimento

O time de engenharia que constrói o Zordon, rodando sobre o próprio Zordon
([ADR-0021](adr/ADR-0021-dois-perfis-de-agente.md)). Pode começar antes, em
forma manual, e ser automatizado aqui.

```text
ZordonKnowledgeBase: parser, chunking, embeddings locais, sqlite-vec
indexação incremental + RAG_STALE bloqueando a Definition of Done
ContextRouter com filtro por securityScope e orçamento
AgentRegistry com os perfis ASSISTANT e ENGINEERING
agentes de engenharia: spec, architecture, documentation, java, javafx,
  testing, codereview, security, defense, devops, mcp, rag, voice, database
orquestração com maxDepth, maxAgents, maxIterations
TokenUsageService + tela Zordon > Usage
UI de execução de tarefa em tempo real
telas de Conhecimento e de Agentes › Engenharia
ChangePlanner + ChangeExecutor com preflight de 9 passos
núcleo de confiança declarado e verificado por ArchUnit
separação fonte/instalado: caminhos de instalação em forbidden
Evaluation Engine com conjuntos em evals/ (ADR-0036)
KnowledgeGraph: relações com fonte na memória (ADR-0037)
versões novas de skills e agentes geradas pelo Zordon, com reversão (ADR-0034)
```

**Pronto quando:** uma tarefa real ("adicione uma tela para MCP") percorre
`SpecAgent → ArchitectureAgent → JavaFxAgent → TestingAgent → CodeReviewAgent →
DocumentationAgent` com orçamento respeitado, consumo atribuído por agente e a
[Definition of Done](process/definition-of-done.md) satisfeita; e o `RagAgent`
responde uma pergunta sobre a arquitetura citando a fonte, com menos de 8.000
tokens de contexto.

E o teste que define o marco: o Zordon implementa uma SPEC **no próprio
repositório**, do preflight ao commit, sem tocar o núcleo de confiança e sem
nenhum passo silencioso — com `ChangeAudit` completo e `userMessageId` não nulo
([ADR-0024](adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md)).

Este marco é o mais fácil de adiar e o de maior retorno composto: cada
funcionalidade posterior é construída mais rápido e com mais consistência.

**Estado (2026-09-19):** a base do marco está de pé, com aprovação delegada; a
execução da auto-modificação **não**, e isso é deliberado.
- **Base de conhecimento** ([SPEC-028](specs/rag/SPEC-028-base-de-conhecimento.md)):
  a documentação vira índice FTS5 com caminho de cabeçalhos, indexação
  incremental por hash, busca com citação dentro do teto de contexto, aviso
  quando o índice está velho, e o agente `rag` que cita arquivo e seção.
- **Engenharia e preflight** ([SPEC-029](specs/process/SPEC-029-engenharia-preflight-e-uso.md)):
  seis agentes de engenharia em TOML (`spec`, `architecture`, `java`, `testing`,
  `codereview`, `documentation`), o preflight de nove passos registrado como
  tarefa que termina esperando o dono, o uso de tokens somado por dia e por
  ator, e duas regras de ArchUnit provando que o núcleo de confiança não depende
  de quem ele controla.

**O que falta para fechar o M8, e por quê:**
- **`ChangeExecutor`** (escrever o código, rodar o `verifyAll` e commitar):
  depende da sandbox ([ADR-0031](adr/ADR-0031-sandbox-para-codigo-de-agente.md)) e de uma
  decisão sua — o Zordon commitar no seu repositório é uma escolha que ninguém
  deve tomar por você. O preflight já produz a entrada que ele vai consumir.
- **Embeddings locais e `sqlite-vec`:** precisam de um modelo com licença e hash
  fixados; é decisão de cadeia de suprimentos.
- **Evaluation Engine com `evals/`**, `KnowledgeGraph` e `ContextRouter` por
  escopo: ficam para quando houver histórico de vereditos suficiente (a tabela
  `verdict` já está gravando desde o M5).
- **Telas próprias** de Conhecimento, Uso e Agentes › Engenharia: hoje tudo isso
  está em `system.diagnostics` e nas seções dos ajustes.

## Interface por marco

A tela de cada marco está em [Layout desktop §3](specs/ui/desktop-layout.md#3-navegação-e-arquitetura-de-informação),
e o visual no [design system](specs/ui/design-system.md). Um marco só fecha com
as telas que o layout lhe atribui e com os critérios de revisão do
[§9](specs/ui/desktop-layout.md#9-handoff-dependências-e-validação) que se
aplicam a elas: resoluções, escala, teclado e leitor de tela, estados offline.
Telas de marcos futuros ficam ocultas ou marcadas como indisponíveis, com
motivo — nunca simulando função.

## Depois do M7

Fora do escopo v1, em ordem provável de valor:

1. Visão de tela (exige ADR próprio — [Visão §6](vision.md#6-visão-de-tela-adiada-mas-planejada))
2. Skills como plugin isolado ([ADR-0012](adr/ADR-0012-skills-in-process-primeiro.md))
3. Cliente CLI (prova que o núcleo é de fato desacoplado)
4. AEC de verdade para barge-in ([Voz §5](specs/voice/design.md#5-barge-in))
5. Mais agentes de projeto
6. Integração profunda com ETW/Sysmon (exige ADR: processo Windows com
   privilégio elevado muda o modelo de ameaça inteiro)

## Dependências

```text
M0 ──► M1 ──► M2 ──► M3 ──► M4 ──► M5 ──► M6 ──► M7 ──► M8
              │              │      │              ▲
              └──────────────┴──────┘              │
                M4 e M5 em paralelo          depende de M3
                depois que M3 fecha          (permissão, auditoria,
                o ToolRegistry                notificação) e de M6
                                              (telemetria dos monitores)
```

M3 é o gargalo: MCP (M4) e agentes (M5) dependem do `ToolRegistry` e do
`PermissionEngine`. Não vale antecipar M4 — MCP sem motor de permissão é
exatamente o cenário que este projeto existe para evitar.

## Riscos por marco

| Marco | Maior risco | Sinal de que deu errado |
|---|---|---|
| M0 | [R1](architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows) — WSL no boot | Precisa abrir o terminal depois de reiniciar |
| M1 | Cache de prompt mal posicionado | `cache_read_input_tokens` sempre zero |
| M2 | Latência e falso positivo de wake word | p50 acima de 2,5 s, ou dispara sozinho |
| M3 | Permission Engine virar burocracia | O usuário começa a clicar "permitir" sem ler |
| M4 | Explosão de contexto por ferramentas | Custo por turno dobra ao conectar o 3º MCP |
| M5 | Memória virar ruído | Fatos recuperados não têm a ver com a pergunta |
| M6 | Tempestade de alertas | O usuário desliga as notificações |
| M7 | Falso positivo erodindo confiança | O usuário começa a clicar "ignorar sempre" |
| M8 | RAG desatualizado vira conhecimento errado | Agentes citam documentação que não corresponde ao código |
| M8 | Auto-modificação enfraquecendo proteções | Um PR do próprio Zordon remove uma verificação e passa despercebido |

As linhas do M3 e do M7 são a mesma doença em dois lugares. Um motor de permissão
que pergunta demais é pior que nenhum, porque treina o usuário a autorizar sem
ler. Um detector que alerta demais é pior que nenhum, porque treina o usuário a
ignorar. Nos dois casos a proteção passa a existir só no diagrama.

Os termômetros são `zordon.permission.decisions` por risco e
`zordon.notify.dismissed_without_reading` por severidade. Se qualquer um sobe, o
que está errado é a política padrão — nunca o usuário.
