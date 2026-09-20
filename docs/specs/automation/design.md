---
document: spec-automation-design
module: automation
section: engine
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [scheduler,watchers,workflow]
specId: null
---

# Automação e monitoramento

## 1. Por que isso é o que justifica um assistente residente

Chat funciona igual num navegador. O que só um processo residente faz é agir
quando ninguém está olhando: verificar a API às 3h, avisar quando o build
terminar, notar que um container caiu. Este documento descreve essa parte.

## 2. Componentes

```text
   ┌───────────────────────────────────────────────────────────┐
   │ AutomationEngine                                          │
   │                                                           │
   │  Scheduler          cron e intervalo                      │
   │  EventWatcher       reage a eventos do EventBus           │
   │  ConditionWatcher   avalia predicado sobre métricas       │
   │  WorkflowEngine     executa a ação da automação           │
   └───────────────┬───────────────────────────────────────────┘
                   │  lê de
                   ▼
   ┌───────────────────────────────────────────────────────────┐
   │ zordon-monitor                                            │
   │  CPU · RAM · GPU · disco · rede · processos               │
   │  Docker (stream de eventos) · WSL · serviços              │
   │  arquivos (inotify) · Git · builds                        │
   └───────────────────────────────────────────────────────────┘
```

## 3. Gatilhos

```toml
# ~/.zordon/automations/api-health.toml
id   = "api-health"
name = "Verificar API a cada 10 minutos"
enabled = true

[trigger]
type  = "interval"
every = "PT10M"
catchUp = "SKIP"          # SKIP | ONCE | ALL

[action]
type = "workflow"
steps = [
  { tool = "skill:dev.httpCheck", args = { url = "http://localhost:8080/health" } },
  { when = "steps[0].status != 200",
    tool = "skill:windows.notify",
    args = { title = "API fora do ar", body = "{{steps[0].error}}" } },
]

[limits]
timeout = "PT30S"
maxFailuresBeforeDisable = 20
```

| Tipo | Semântica |
|---|---|
| `schedule` | Expressão cron com fuso; `catchUp` define o que fazer com disparos perdidos |
| `interval` | A cada N; relógio monotônico, imune a salto de relógio |
| `event` | Reage a um tipo de evento do barramento, com filtro JSON |
| `condition` | Predicado sobre métricas, com `checkEvery` e `sustainedFor` |

`catchUp` responde diretamente ao risco
[R2](../../architecture/windows-wsl.md#r2--o-wsl-é-derrubado-por-fora): o WSL ficou
desligado 6 horas, o job de 10 minutos perdeu 36 disparos. `SKIP` (padrão) ignora,
`ONCE` roda uma vez ao voltar, `ALL` roda todos — e `ALL` só faz sentido para
jobs idempotentes de coleta.

## 4. Histerese e deduplicação

"Zordon, me avise se a CPU passar de 90%" é uma armadilha: durante um build, a
CPU cruza 90% cinquenta vezes por minuto.

```text
                    ┌── dispara aqui ──┐
   90% ─────────────┼──────────────────┼──────────────
                    │                  │
   80% ── rearma ───┴──────────────────┴──────────────
         aqui
        │◄── sustainedFor: 60 s ──►│
```

Três mecanismos combinados:

1. **`sustainedFor`** — a condição precisa ser contínua pelo período. Um pico de
   2 s não dispara.
2. **Histerese** — dispara em 90%, só rearma abaixo de 80%. Sem isso, oscilar em
   torno do limiar gera uma tempestade.
3. **Janela de silêncio** (`cooldown`, padrão 15 min por automação) — depois de
   disparar, não dispara de novo no período, mesmo que rearme.

Notificações do mesmo alerta são **coalescidas**: "Container api caiu (3× nos
últimos 10 min)" em vez de três notificações.

## 5. Coleta sem polling

Requisito explícito do briefing: evitar polling excessivo. Fontes por natureza:

| Fonte | Mecanismo | Frequência |
|---|---|---|
| CPU, RAM | leitura de `/proc/stat`, `/proc/meminfo` | 1 Hz (amostragem, barata) |
| GPU | `nvidia-smi` em modo *daemon* ou NVML | 0,5 Hz, só se houver GPU |
| Disco | `statvfs` | 0,1 Hz |
| Rede | `/proc/net/dev` | 1 Hz |
| Processos | `/proc` | 0,2 Hz, e sob demanda |
| **Docker** | **stream de eventos da API** (`/events`) | **push, sem polling** |
| **Arquivos** | **inotify** | **push** |
| Git | inotify em `.git/HEAD` e `.git/refs` | push |
| Builds | processo filho; observa a saída | push |
| Serviços Windows | consulta via host | sob demanda |
| WSL | `/proc`, `wslinfo` | 0,1 Hz |

O `zordon-monitor` é também a fonte de telemetria do `DetectionEngine`
([Defesa §4](../../security/defense.md#4-detection-engine)) — a mesma amostragem
serve ao painel e aos detectores, sem custo duplicado. Foi por isso que a
detecção comportamental coube no orçamento de <3% de CPU: ela reusa observação
que já estava sendo feita.

A regra: **se existe API de eventos, use-a.** Docker e inotify cobrem os casos
que mais importam (container caiu, arquivo mudou, branch trocou) sem custo
contínuo. O que sobra de amostragem é leitura de `/proc`, que custa microssegundos.

Adaptação de frequência: com nenhum cliente assinando `system` e nenhuma
automação de condição ativa, a amostragem cai para 0,2 Hz. Não há razão para
medir CPU a 1 Hz se ninguém vai olhar — e isso é o que mantém o requisito de <3%
de CPU em repouso de [Visão §5](../../vision.md#recursos).

## 6. `WorkflowEngine`

Workflows são declarativos e propositalmente limitados. Eles **não** são uma
linguagem de programação.

```toml
steps = [
  { id = "check", tool = "skill:dev.httpCheck", args = {...} },
  { id = "logs",  when = "check.status != 200",
                  tool = "mcp:docker.logs", args = { container = "api", tail = 50 } },
  { id = "ask",   when = "check.status != 200",
                  agent = "developer",
                  task = "Analise estes logs e diga a causa provável: {{logs.output}}" },
  { id = "tell",  tool = "skill:windows.notify",
                  args = { title = "API fora", body = "{{ask.summary}}" } },
]
```

O que existe: passos sequenciais, `when` com expressões simples de comparação,
interpolação de resultados anteriores, invocação de agente como passo.

O que **não** existe e não vai existir: laços, funções, recursão, código
arbitrário. Quando um workflow precisa de lógica de verdade, o passo certo é
invocar um agente — ele é o lugar onde julgamento acontece.

### Execução durável

Um workflow pode levar horas (esperar um build, repetir uma checagem) e o WSL
pode cair no meio ([R2](../../architecture/windows-wsl.md#r2--o-wsl-é-derrubado-por-fora)).
A execução usa o `TaskStore` do [Planner](../agents/planner.md#4-taskstore)
([ADR-0035](../../adr/ADR-0035-planos-duraveis-e-estado-de-tarefas.md)): cada
disparo é uma tarefa, cada passo uma etapa com estado persistido.

| Capacidade | Como |
|---|---|
| Pausa e retomada | `pause` leva a execução a `blocked`; `resume` continua da etapa seguinte à última concluída |
| Retry | Por passo: `retry = { attempts = 3, backoff = "30s" }`; só em passos marcados `idempotent = true` ou GREEN de leitura |
| Continuação depois de erro | `onError = "stop" \| "skip" \| "notify"` por passo; o padrão é parar e notificar |
| Queda do núcleo | Execuções `running` voltam como `blocked`; passo com efeito não é repetido sem decisão |
| Idempotência | Chave `(automation_id, scheduled_for, step_id)`: um passo concluído não roda de novo no mesmo disparo |

Os passos fixam a versão das skills e agentes que chamam
([Extensões §4](../../architecture/extensions.md#4-versões-e-reversão)).

## 7. Segurança de automações

Automação é a superfície mais perigosa do sistema: ela roda sem ninguém olhando,
repetidamente, e foi criada a partir de linguagem natural.

| Controle | Regra |
|---|---|
| Escalonamento | Ação disparada por automação sobe +1 nível de risco |
| Teto absoluto | Automação **nunca** executa ação RED, nem com confirmação prévia |
| Exclusão | Impossível — não existe no sistema ([ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md)) |
| Notificação | Todo disparo com efeito colateral notifica ([ADR-0014](../../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md)) |
| Lockdown | Todas as automações ficam suspensas |
| Confirmação na criação | A spec resolvida é mostrada e aprovada antes de persistir |
| Ator na auditoria | `automation:<id>`, distinto de `user` |
| Desabilitação automática | 20 falhas consecutivas → desabilita e notifica |
| Orçamento | Automações compartilham um teto de custo diário próprio |
| Kill switch | "Pausar Zordon" suspende todas as automações |

A regra do teto absoluto merece ênfase: "Zordon, toda noite apague os logs
antigos" é um pedido que o sistema **recusa duas vezes** — primeiro porque
exclusão não existe, depois porque automação não executa RED. O que ele oferece
no lugar: uma automação que move os logs antigos para a quarentena, notifica, e
deixa a restauração a um clique. Isso atende à intenção real ("libere espaço,
tire isso do caminho") sem a propriedade perigosa.

A diferença entre as duas é a diferença entre um assistente e um acidente
esperando para acontecer — com a vantagem de que, no segundo caso, o acidente
aconteceria às 3 da manhã, sem ninguém olhando.

## 8. Notificação com a UI fechada

UC9 — o container cai às 3h da manhã, o `zordon-desktop` está fechado.

```text
   Docker /events → container "api" died
        │
        ▼
   EventWatcher casa a automação
        │
        ▼
   WorkflowEngine executa
        │
        ├─► zordon-host conectado?
        │      sim → bridge.notify → notificação nativa do Windows
        │      não → enfileira
        │
        └─► persiste em notification_queue
                │
                ▼
        na próxima conexão do desktop/host:
        entrega as pendentes, agrupadas
```

A fila de notificações é durável e tem retenção de 24 h. Notificação de 3 dias
atrás não serve para nada e só gera ruído ao ligar o PC.
