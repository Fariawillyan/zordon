---
document: spec-agents-planner
module: agents
section: planner
version: 1
updatedAt: 2026-09-18
securityLevel: internal
tags: [planner,planejamento,tarefas,estado,retomada,dag,sqlite]
specId: null
---

# Planner e estado de tarefas

Como "quero fazer X" vira um plano executável e como esse plano sobrevive a
quedas. Decisão em [ADR-0035](../../adr/ADR-0035-planos-duraveis-e-estado-de-tarefas.md).

## 1. Quando há plano

| Pedido | Plano? |
|---|---|
| Pergunta, consulta, uma ferramenta | Não: turno simples ([Orquestração §2](../../agents/orchestration.md#2-pipeline)) |
| Mais de uma etapa com dependência, ou mais de um agente | Sim |
| Qualquer alteração de projeto | Sim, e com o preflight de [Auto-modificação §6](../../process/self-modification.md#6-preflight-obrigatório) |
| Automação | O workflow aprovado já é o plano ([Automação §6](../automation/design.md#6-workflowengine)) |

## 2. O plano

```text
Plan
  goal            o que o usuário pediu, com as palavras dele
  budget          tokens, US$, tempo
  origin          quem pediu (Identidade)
  steps[]         DAG
    id, title       título curto, é o que a voz narra
    requires        capacidades (ADR-0033)
    dependsOn[]     ids
    risk            GREEN | YELLOW | RED, estimado
    doneWhen        critério verificável (ADR-0036)
    agent?          escolhido pelo Orchestrator, não pelo Planner
```

O **Planner** é um agente com um único trabalho: produzir o `Plan`. Ele não
executa nada e não escolhe agentes — isso é do Orchestrator, pelo registro de
capacidades. Um plano com efeito é **comunicado** antes de começar
([ADR-0014](../../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md)); com risco RED,
aguarda aprovação.

## 3. Estados

```text
 planned ─► ready ─► running ─► verifying ─► done
                │        │           │
                │        ├─► waiting_approval ─► ready
                │        ├─► failed ──► (retry) ready
                │        └─► blocked (dependência falhou, núcleo reiniciou)
                └────────────► cancelled (a qualquer momento, pelo usuário)
```

`done` só com veredito do Verifier ([Avaliação](evaluation.md)). Cada transição é
um evento (`TASK_STATE`) e uma linha no `TaskStore`.

## 4. `TaskStore`

SQLite, no mesmo banco da memória ([Memória §2](../memory/design.md#2-tecnologia)):

```sql
CREATE TABLE task (id TEXT PRIMARY KEY, goal TEXT, origin TEXT, state TEXT,
                   budget_json TEXT, created_at TEXT, updated_at TEXT);
CREATE TABLE task_step (task_id TEXT, id TEXT, title TEXT, requires TEXT,
                        depends_on TEXT, risk TEXT, done_when TEXT, agent TEXT,
                        state TEXT, attempts INTEGER, result_json TEXT,
                        PRIMARY KEY (task_id, id));
CREATE TABLE task_transition (task_id TEXT, step_id TEXT, from_state TEXT,
                              to_state TEXT, reason TEXT, at TEXT);
```

- O resultado de uma etapa é gravado **antes** de a próxima começar.
- Transições só acrescentam; nada é apagado.

## 5. Retomada depois de queda

O WSL cai sem aviso ([R2](../../architecture/windows-wsl.md#r2--o-wsl-é-derrubado-por-fora)).
Ao subir, o núcleo:

1. lê etapas `running` e as leva a `blocked` com motivo "o núcleo reiniciou";
2. narra: "Uma tarefa foi interrompida: `<goal>`. Quer que eu continue?";
3. retoma só com decisão do usuário ou quando a etapa é declarada idempotente e
   sem efeito colateral (ex.: leitura).

Nada com efeito é reexecutado sozinho: a etapa pode ter feito metade.

## 6. Voz

O narrador fala o **título da etapa** quando ela começa, agrupado pela janela da
[SPEC-012](../voice/SPEC-012-voice-first-narracao-e-estados.md) — "Executando os
testes", "Codex está corrigindo e Claude está revisando" —, nunca cada
ferramenta. `waiting_approval` vira estado `attention` e a frase de autorização.

## 7. Interfaces

```java
public interface Planner { Plan plan(Request request, Context context); }

public interface TaskStore {
    TaskId create(Plan plan);
    void transition(TaskId task, StepId step, StepState to, String reason);
    void result(TaskId task, StepId step, StepResult result);
    List<Task> interrupted();          // running no momento da queda
}
```

## 8. Casos de erro

| Caso | Comportamento |
|---|---|
| Plano com ciclo de dependências | Recusado; o Planner refaz uma vez, depois falha com motivo |
| Etapa sem critério verificável | Marcada; conclusão exige confirmação humana |
| Orçamento estoura | Etapa atual para; plano em `blocked`; narrador avisa |
| Dependência falha | Dependentes vão a `blocked`; o resto do plano segue |

## 9. Marco

M5, com o `AgentOrchestrator`. O Workflow Engine do M6 reutiliza o `TaskStore`.
