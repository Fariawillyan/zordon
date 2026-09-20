---
document: spec-023
module: agents
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,planner,taskstore,verifier,retomada,m5]
specId: SPEC-023
---

# SPEC-023 — Planos duráveis e conclusão verificada

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `ArchitectureAgent` |
| **Revisores** | SecurityAgent, DatabaseAgent |
| **Marco** | M5 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

Um pedido de vários passos vira um plano que sobrevive a quedas do WSL. Cada
etapa só conta como concluída com o veredito de um verificador independente
([ADR-0035](../../adr/ADR-0035-planos-duraveis-e-estado-de-tarefas.md),
[ADR-0036](../../adr/ADR-0036-conclusao-verificada.md), [Planner](planner.md),
[Avaliação](evaluation.md)).

## 2. Problema

Um turno longo morre com o núcleo, e o que foi feito até ali se perde. E
"pronto" hoje é o que o modelo diz: ninguém confere.

## 3. Escopo

**Pedido**
- Por voz ou texto: "planeje e faça: …", "faça passo a passo: …" (rota rápida
  `task.create`, que só o usuário pede; o modelo não abre tarefas).
- Pela tela: `task.create {goal}`.

**Planner**
- O modelo (papel `agent_heavy`, ou `conversation`) devolve um plano em JSON:
  - `goal`;
  - `steps[]`, cada uma com `id`, `title` (é o que a voz narra), `agent`,
    `dependsOn[]`, `risk` e `doneWhen`.
- Validação:
  - de 1 a 10 etapas, `id` únicos e dependências que existem;
  - sem ciclo;
  - agente conhecido; senão, `zordon`.
- Plano inválido: o Planner refaz **uma** vez, com o motivo. Na segunda vez, a
  tarefa falha com o motivo.

**Critério de conclusão (`doneWhen`)**

| Tipo | Forma | Quem verifica |
|---|---|---|
| `tool` | `{type, tool, args, expect}` | Roda a ferramenta (GREEN, de leitura) e procura `expect` no resultado |
| `judgement` | `{type, criterion}` | O Verifier, outro agente, com as **evidências** (saídas das ferramentas da etapa), não com a narrativa |
| `human` | `{type, criterion}` | Confirmação na tela (`task.confirm`) |

- `command` fica para quando houver sandbox; até lá, vira `human`.
- Critério `judgement` numa etapa com risco acima de GREEN vira `human`: o
  julgamento nunca é o único critério de algo com efeito.

**Execução (orquestrador)**
- As etapas prontas rodam uma de cada vez, em ordem topológica. Cada uma é uma
  execução de agente da SPEC-022, com o agente da etapa, o teto dele e o
  orçamento da tarefa.
- A etapa recebe o objetivo, o próprio título e os resultados das dependências,
  marcados como dado.
- Orçamento da tarefa, compartilhado pelas etapas: 40 passos, 60 chamadas, 400
  mil tokens e 30 min.

**Estados** ([Planner §3](planner.md#3-estados))
- Etapa: `planned` → `running` → `verifying` → `done`, `failed` ou
  `waiting_human`.
- Tarefa: `running`, `done`, `failed`, `waiting_human`, `cancelled` ou
  `blocked`.
- Etapa que falha deixa as dependentes em `blocked`. O resto do plano segue.
- A tarefa só fica `done` quando todas as etapas estão `done`.

**`TaskStore`** (no `zordon.db`, migração V002)
- Tabelas: `task`, `task_step`, `task_transition` e `verdict`.
- O resultado de uma etapa é gravado **antes** de a próxima começar.
- Transições só acrescentam; nada é apagado.

**Retomada**
- Ao subir, etapas `running` ou `verifying` vão para `blocked` com "o núcleo
  reiniciou", e a tarefa vai para `blocked`.
- Sai o aviso "Uma tarefa foi interrompida: <objetivo>. Quer que eu continue?".
- Continuar é `task.resume`, só da tela. Nada é reexecutado sozinho, porque a
  etapa pode ter feito metade.

**Registro de avaliação:** cada veredito vira uma linha em `verdict` (tarefa,
etapa, agente, modelo, veredito, tokens, duração). É a base do Evaluation Engine
do M8.

**Voz:**
- Ao começar, cada etapa narra o título.
- "Tarefa concluída" só depois do último `pass`.
- `waiting_human` vira `attention` com "Preciso que você confira o resultado.";
  falha vira `error`, com o motivo.

## 4. Não escopo

- Critério `command` no sandbox (depende da sandbox, ADR-0031).
- Paralelismo entre etapas independentes.
- Conjuntos de avaliação e troca de modelo guiada por eles (M8).
- Retomada automática de etapas declaradas idempotentes.

## 5. Arquitetura

```text
"planeje e faça: X" ─► task.create ─► Planner (modelo, JSON validado) ─► TaskStore (V002)
                                                                              │
                        TaskRunner ─► etapa pronta ─► AgentRunner (SPEC-022) ─┤ resultado + evidências
                                           │                                  │
                                           ▼                                  ▼
                                       Verifier (tool | judgement | human) ─► verdict + transição
                                                                              │
                                                              TASK_STATE ─► narrador, tela
```

## 6. Fluxo

1. "Zordon, planeje e faça: veja por que a API caiu e anote a causa".
2. O Planner devolve duas etapas:
   - `s1` "Ver os containers e os logs" (`developer`, `judgement`);
   - `s2` "Anotar a causa na memória" (`zordon`, `human`).
3. Resposta falada: "Plano com 2 etapas: Ver os containers e os logs; Anotar a
   causa na memória. Começando."
4. `s1` roda, e o Verifier recebe as saídas de `docker.ps` e `docker.logs` e
   devolve `pass`.
5. `s2` roda e vai para `waiting_human`. O usuário confirma na tela, e a tarefa
   fica `done`.

## 7. Interfaces

```java
public interface TaskStore {                              // zordon-memory
    String createTask(String goal, String origin, List<PlanStep> steps);
    void taskState(String taskId, String to, String reason);
    void stepState(String taskId, String stepId, String to, String reason);
    void stepResult(String taskId, String stepId, String resultJson);
    void verdict(Verdict verdict);
    List<TaskView> tasks(int limit);
    Optional<TaskView> task(String taskId);
    List<TaskView> interrupted();
    Map<String, Object> taskStats();
}
```

| Método ZWP | Params | Retorno |
|---|---|---|
| `task.create` | `{goal}` | `{taskId, steps[]}` |
| `task.list` | `{limit?}` | `{tasks[{taskId, goal, state, steps, done}]}` |
| `task.get` | `{taskId}` | `{task, steps[{id, title, agent, state, doneWhen, result?, verdict?}]}` |
| `task.cancel` | `{taskId}` | `{cancelled}` |
| `task.resume` | `{taskId}` | `{resumed}`; só de um desktop |
| `task.confirm` | `{taskId, stepId, pass}` | `{state}`; só de um desktop |

## 8. Eventos

`TASK_STATE {taskId, stepId?, state, title?, reason?}` no tópico `agents`.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Tarefas, etapas e transições | `zordon.db` (V002) | Permanente; nada é apagado |
| Vereditos | `zordon.db`: `verdict` | Permanente |

## 10. Segurança

- **Cada ferramenta de cada etapa** passa pelo motor com o teto do agente da
  etapa. O plano não autoriza nada por si.
- **O Verifier não executa efeito:**
  - `tool` só aceita ferramenta GREEN e visível ao modelo;
  - `judgement` roda sem ferramentas.
- **O resultado de uma etapa entra na seguinte como dado**, marcado.
- **Retomar é decisão do usuário, na tela.**

## 11. Permissões

- `task.create` pela rota do usuário é GREEN: abrir o plano não faz nada no
  sistema.
- `task.resume` e `task.confirm` só vêm do desktop.

## 12. Observabilidade

- Log INFO a cada transição.
- `system.diagnostics.tasks` com a contagem por estado e os vereditos dos
  últimos 7 dias.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Plano com ciclo, vazio ou com mais de 10 etapas | Refeito uma vez; depois, falha com o motivo |
| JSON inválido do Planner | Igual ao plano inválido |
| Orçamento da tarefa estourado | A etapa atual para, a tarefa vai para `blocked`, e o narrador avisa |
| Verifier sem modelo | `inconclusive` → `waiting_human` |
| Etapa falha | As dependentes vão para `blocked`; o resto segue |

## 14. Testes

- `TaskStore`: migração V002, criação, transições só acrescentam, resultado antes
  da próxima etapa, `interrupted`.
- Planner: plano válido; ciclo refeito uma vez; agente desconhecido → `zordon`;
  mais de 10 etapas recusado.
- Verifier:
  - `tool` com `expect` presente e ausente;
  - `judgement` com evidência e sem a narrativa;
  - `judgement` em etapa YELLOW → `human`.
- Execução ponta a ponta com provider roteirizado: ordem, dependência que falha,
  `waiting_human` e `task.confirm`.
- Retomada: núcleo reaberto → `blocked` e aviso; `task.resume` continua.
- Voz: títulos narrados e "Tarefa concluída" só no fim.

### Evidências (2026-09-19)

- `TasksTest` (6 testes, com `TaskStore` real e provider roteirizado):
  - CA-1: "planeje e faça: …" vira `task.create`; a resposta fala o plano, e
    `planned` sai antes de qualquer etapa rodar;
  - CA-2: `tool` com o texto ausente reprova; `judgement` recebe o critério e,
    sem ferramenta, a resposta marcada "sem evidência"; `human` espera a tela;
    `task.confirm` fecha a etapa;
  - CA-3: a etapa que falha bloqueia a dependente, que nunca roda; a
    independente conclui;
  - CA-4: tarefa "rodando" no banco vira `blocked` com "o núcleo reiniciou",
    nada roda sozinho, e `task.resume` continua;
  - o Planner refaz o plano com ciclo uma vez, cai no `zordon` quando o agente é
    desconhecido, transforma julgamento de etapa com efeito em humano, recusa
    mais de 10 etapas e ferramenta que não confere;
  - a voz fala o título da etapa, e "Tarefa concluída." só no fim.
- `MemoryStoreTest.tarefaGuardaEtapasTransicoesEVereditosSemApagar` (CA-5):
  gatilhos recusam `DELETE` e `UPDATE` em transições e vereditos.

## 15. Critérios de aceite

- `CA-1` Dado "planeje e faça: X", então há um plano validado, gravado e
  comunicado antes de começar, com as etapas faladas pelo título.
- `CA-2` Dada uma etapa concluída pelo agente, então ela só fica `done` com o
  `pass` do Verifier. `fail` e `inconclusive` levam a `failed` e a
  `waiting_human`.
- `CA-3` Dada uma etapa que falha, então as dependentes ficam `blocked` e as
  independentes seguem.
- `CA-4` Dado o núcleo reiniciado no meio de uma tarefa, então ela fica
  `blocked`, o usuário é avisado, e nada é reexecutado sem `task.resume`.
- `CA-5` Dado cada veredito, então ele é gravado com agente, modelo, tokens e
  duração, e as transições nunca são apagadas.

## 16. Impacto em outros módulos

- `zordon-memory`: migração V002 e `SqliteTaskStore`.
- `zordon-core`:
  - pacote `tasks` (`Planner`, `Verifier`, `TaskRunner`, `TaskTools`);
  - `AgentRunner` passa a devolver as evidências;
  - `IntentRouter` ganha a rota "planeje e faça";
  - `ActivityInterpreter` narra `TASK_STATE`.
- `zordon-api`: `TASK_STATE`.

## 17. Dependências

- [SPEC-022](SPEC-022-agentes-como-configuracao.md) ·
  [SPEC-021](../memory/SPEC-021-memoria-de-longo-prazo.md) ·
  [Planner](planner.md) · [Avaliação](evaluation.md)
