---
document: roadmap
module: product
section: roadmap
version: 1
updatedAt: 2026-09-17
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

## M2 — Voz

```text
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
10. Skills GREEN (leitura, métricas, abrir app do catálogo)
11. Skills YELLOW (escrever arquivo, build, Git)
12. Skills RED (quarentena, suspender processo) + Quarantine Vault
```

**Pronto quando:** "Zordon, abra o IntelliJ" funciona; "Zordon, apague os logs do
projeto" é **recusado** e o Zordon oferece a quarentena no lugar; o diálogo RED
lista os 43 arquivos, diz que é reversível e nega ao expirar; a cadeia de
auditoria verifica; ArchUnit reprova qualquer `ProcessBuilder` fora de
`zordon-security` e qualquer `Files.delete` fora do cofre.

O `NotificationCenter` vem **antes** da primeira Skill com efeito colateral. A
invariante de [Comunicação §1](security/communication.md#1-o-princípio-fundamental)
não admite período de transição — nem em desenvolvimento, porque é aí que o
hábito se forma.

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

## M5 — Agentes e memória

```text
Agent como configuração TOML
AgentOrchestrator com orçamentos e delegação
os 6 agentes iniciais
MemoryStore: SQLite, FTS5, sqlite-vec
recuperação híbrida com RRF
destilação assíncrona
UI de agentes e de memória
```

**Pronto quando:** UC4 ("veja por que minha API caiu") percorre múltiplos passos
respeitando o orçamento; UC5 ("abra o projeto que trabalhamos ontem") recupera o
contexto certo ou pergunta; criar um agente novo é criar um arquivo TOML.

## M6 — Automação e monitoramento

```text
Scheduler, EventWatcher, ConditionWatcher, WorkflowEngine
zordon-monitor com fontes push (Docker events, inotify)
histerese, deduplicação, janela de silêncio
fila durável de notificações
tela de Diagnostics completa
```

**Pronto quando:** UC6, UC7 e UC9 funcionam; um container caindo às 3h com tudo
fechado gera notificação; o consumo de CPU em repouso com escuta ativa fica
abaixo de 3%.

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
ChangePlanner + ChangeExecutor com preflight de 9 passos
núcleo de confiança declarado e verificado por ArchUnit
separação fonte/instalado: caminhos de instalação em forbidden
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
