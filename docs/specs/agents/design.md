---
document: spec-agents-design
module: agents
section: runtime
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [agente,orquestrador,orcamento,delegacao]
specId: null
---

# Agentes — runtime e orquestração

## 1. O que é um agente aqui

Um agente **não** é uma classe com lógica própria. É uma **configuração** que
compõe cinco coisas sobre o mesmo runtime:

```text
Agent = prompt de sistema
      + escopo de ferramentas
      + teto de permissão
      + política de modelo
      + orçamento padrão
```

Isso é deliberado: se agente fosse código, adicionar um agente exigiria
recompilar o núcleo, violando o princípio 3 de
[Visão §3](../../vision.md#3-princípios-de-design). Agentes vivem em
`~/.zordon/agents/*.toml` e são carregados na inicialização e sob demanda.

```toml
# ~/.zordon/agents/developer.toml
id = "developer"
name = "DeveloperAgent"
description = "Git, builds, testes, análise de logs, desenvolvimento"

prompt = """
Você é o DeveloperAgent do Zordon...
"""

[tools]
include = ["skill:dev.*", "skill:files.*", "mcp:git.*", "mcp:docker.*"]
exclude = ["skill:dev.deploy"]
pinned  = ["skill:dev.gitStatus", "skill:files.read"]

[permissions]
ceiling = "YELLOW"

[model]
primary  = { role = "agent_heavy" }
fallback = { role = "agent_light" }

[budget]
maxSteps = 15
maxTokens = 120000
wallClock = "PT5M"
maxCost = "USD 0.25"
```

`pinned` são ferramentas que **sempre** entram no prompt, independentemente da
seleção semântica. São as que o agente usa em quase todo turno; garantir a
presença delas evita uma volta extra do laço.

## 2. Os agentes iniciais

| Agente | Teto | Modelo | Domínio |
|---|---|---|---|
| `zordon` (geral) | YELLOW | `agent_light` | Fallback; conversa; roteia quando o Router não decide |
| `system` | YELLOW | `agent_light` | CPU, RAM, GPU, processos, disco, rede, serviços, WSL, Docker |
| `developer` | YELLOW | `agent_heavy` | Git, Java, Maven, Gradle, React, C++, Unreal, builds, testes, logs |
| `research` | **GREEN** | `agent_light` | Pesquisa e coleta de informação |
| `automation` | YELLOW | `agent_light` | Criar, listar e gerir automações |
| `projeto-<nome>` | YELLOW | `agent_heavy` | Um projeto específico do usuário |

O `zordon` geral não estava no briefing e é necessário: sem ele, toda intenção
que o Router não classifica morre sem resposta.

**`research` tem teto GREEN e isso é a decisão de segurança mais importante deste
documento.** Ele consome a fonte mais provável de prompt injection (web, docs de
terceiros, issues). Com teto GREEN ele não pode escrever arquivo, executar
processo nem chamar o bridge — mesmo que seja completamente convencido por um
conteúdo malicioso. Ver [Segurança §6](../../security/model.md#6-prompt-injection).

### SystemAgent

Ferramentas: `skill:system.*`, `mcp:docker.*` (leitura), `skill:process.list`,
métricas do `zordon-monitor`.

Comportamento específico: consultas de estado devem usar o cache de métricas do
monitor (atualizado continuamente) em vez de disparar uma coleta nova. "Como está
a CPU?" responde em milissegundos com o último valor amostrado, não esperando
uma amostragem.

### DeveloperAgent

Ferramentas: Git, Maven, Gradle, npm, Docker, leitura/escrita de arquivos em
workspaces, busca em código, leitura de logs.

Comportamentos específicos:

- **Sempre estabelece contexto antes de agir.** Antes de sugerir um comando
  Git, roda `git status` e `git branch`. Antes de um build, confere qual
  ferramenta o projeto usa.
- **Build é uma operação longa.** Builds rodam como tarefa assíncrona com eventos
  `AGENT_PROGRESS`, não bloqueando o turno. É o que torna "me avise quando o
  build terminar" (UC7) natural.
- **Análise de log é sumarização, não despejo.** Um log de 200 MB não entra no
  contexto: a Skill filtra por nível, janela de tempo e padrão, e devolve as
  linhas relevantes mais um resumo de contagem.
- Executa builds de projetos que vivem no Windows **pelo host** quando possível,
  evitando a penalidade de I/O em `/mnt/c`
  ([R9](../../architecture/windows-wsl.md#r9--io-em-mntc-é-ordens-de-grandeza-mais-lento)).

### ResearchAgent

Ferramentas: busca web, fetch de página, leitura de arquivo (somente leitura),
busca na memória.

Comportamento: cita fonte para toda afirmação factual. Como não pode escrever,
quando o resultado precisa ser salvo ele devolve o conteúdo e o agente geral (ou
o usuário) faz a escrita — uma barreira de privilégio deliberada no meio do
fluxo.

### AutomationAgent

Ferramentas: `automation.*`, leitura do catálogo de ferramentas.

Comportamento: ao criar uma automação a partir de linguagem natural, **sempre**
mostra a especificação resolvida para confirmação antes de persistir. "Verifica
minha API a cada 10 minutos" vira uma spec concreta (qual URL, qual condição de
falha, o que fazer ao falhar) que o usuário aprova. Automação é código que roda
sozinho depois; criar por adivinhação é criar um problema futuro.

### Agente de projeto

Não é um agente único: é um **molde**. Cada projeto do usuário pode ter o seu,
com o conhecimento do repositório, as convenções e a arquitetura daquele projeto
no prompt, e escopo de ferramentas restrito aos caminhos dele.

Convenção de nome: `projeto-<nome>` — por exemplo `projeto-aurora`, usado como
exemplo ao longo desta documentação.

O teste do design está aqui: `~/.zordon/agents/` deve poder receber um
`projeto-<nome>.toml` novo sem nenhuma alteração de código. Se criar o segundo
agente de projeto exigir tocar no núcleo, o design falhou.

## 3. Runtime

```text
  start(agent, task, budget)
        │
        ▼
  ┌──────────────────────────────────────┐
  │ AgentRun  (estado, cancelável)       │
  │   status: RUNNING                    │
  │   steps: 0/15   tokens: 0/120k       │
  │   deadline: now + 5min               │
  └───────────┬──────────────────────────┘
              ▼
     ┌────────────────────────┐
     │ monta contexto         │ prompt + ferramentas + memória + histórico
     ├────────────────────────┤
     │ chama o provider       │ streaming
     ├────────────────────────┤
     │ há tool_use?           │
     │   sim → executa (§9.5) │ ──► permissão ──► auditoria
     │   não → finaliza       │
     ├────────────────────────┤
     │ verifica orçamento     │ estourou? finaliza com parcial
     └───────────┬────────────┘
                 │ repete
                 ▼
     AGENT_FINISHED {ok, reason, usage, cost}
```

Estados: `RUNNING`, `WAITING_PERMISSION`, `DONE`, `FAILED`, `CANCELLED`,
`BUDGET_EXCEEDED`, `SUSPENDED_BY_BREAKER`.

`SUSPENDED_BY_BREAKER` é terminal para a execução e só sai por decisão do
usuário ([ADR-0018](../../adr/ADR-0018-circuit-breaker-e-lockdown.md)). O agente não
retenta, não espera e não se recupera sozinho.

`WAITING_PERMISSION` é um estado de primeira classe, não uma pausa interna: ele
aparece na UI, não conta para o teto de tempo de parede, e tem o seu próprio
timeout (60 s → nega).

## 4. Delegação

Um agente pode delegar para outro via a ferramenta `agent.delegate`:

```text
  developer  ──delegate──►  research   "descubra a sintaxe atual de X"
       │                        │
       │                        └──► resultado (texto, sem efeito colateral)
       ▼
  continua com o resultado
```

Regras da delegação:

1. **Profundidade máxima 2.** Um sub-agente não delega. Sem isso, um laço de
   delegação consome o orçamento em cascata de forma difícil de diagnosticar.
2. **Orçamento vem do pai.** O filho gasta do orçamento restante do pai, não de
   um novo. Isso é o que impede a delegação de contornar o teto de custo.
3. **Teto de permissão é o mínimo dos dois.** Delegar nunca eleva privilégio.
4. **O resultado volta como dado, não como instrução.** Mesmo envelopamento de
   resultado de ferramenta, mesma marca de não-confiabilidade.
5. Cada delegação gera `AGENT_STARTED`/`AGENT_FINISHED` próprios, visíveis na UI
   aninhados sob o pai.

Delegação é útil quando o sub-trabalho consome muito contexto que o pai não
precisa guardar (ler 15 arquivos para extrair uma resposta). Não é útil para
paralelizar duas ferramentas independentes — para isso serve a chamada paralela
de ferramentas, que é mais barata.

## 5. Circuit breaker de agente

Um agente é um sujeito vigiado pelo `ZordonSafetyCircuitBreaker`
([Defesa §7](../../security/defense.md#7-zordonsafetycircuitbreaker)). O que abre
o disjuntor de um agente:

| Gatilho | Limiar |
|---|---|
| Sequência de ferramentas anômala (`ai.tool-sequence`) | score > 0,8 |
| Ações negadas em sequência | 3 pelo mesmo agente em 1 min |
| Tentativa de efeito acima do próprio teto | 1 ocorrência |
| Tentativa de tocar política, capacidade ou auditoria | 1 ocorrência |
| Consumo de orçamento anômalo | 3× a mediana histórica do agente |
| Repetição improdutiva | 5 chamadas idênticas consecutivas |

Ao abrir: bloqueia novas ações → revoga capacidades → cancela a execução →
preserva o contexto completo como evidência → registra → **notifica o usuário
imediatamente**. Nenhuma alteração adicional é permitida até a autorização dele.

Isto é o que contém um agente sequestrado por injeção de prompt: mesmo que o
conteúdo convença o modelo a mudar de objetivo, a mudança de comportamento
aparece como sequência anômala e o disjuntor abre antes que o padrão se complete.

## 6. Seleção de agente

```text
  IntentRouter decide  ──► agente explícito
         │
         │ confiança baixa
         ▼
  ZordonAgent geral
         │
         │ percebe que é trabalho de outro
         ▼
  delega uma vez
```

O usuário pode forçar: "Zordon, pergunta pro DeveloperAgent..." ou, na UI,
selecionando o agente antes de enviar. Forçar sempre vence o roteador.

## 7. Observabilidade de agente

Cada execução registra: agente, tarefa, passos, ferramentas chamadas (em ordem),
tokens de entrada/saída/cache, custo, duração por etapa, motivo do término.

Isso alimenta a tela de Diagnostics e responde às perguntas que realmente
aparecem na prática:

- Por que esse turno demorou 40 s? (uma ferramenta lenta? muitas voltas?)
- Por que custou US$ 0,30? (contexto grande? cache não pegou?)
- Por que o agente não usou a ferramenta certa? (ela não estava selecionada?)

E, desde que o disjuntor existe, uma quarta: por que este agente foi suspenso?
A resposta é a lista de sinais que o abriram, com as evidências preservadas.

A terceira é a mais comum, e a resposta quase sempre está na seleção de
ferramentas — por isso o registro de **quais ferramentas foram oferecidas** ao
modelo, e não só quais foram chamadas, é obrigatório.
