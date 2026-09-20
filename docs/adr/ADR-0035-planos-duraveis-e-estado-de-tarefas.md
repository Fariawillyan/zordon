---
document: adr-0035
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,planner,estado,tarefas,retomada]
specId: null
---

# ADR-0035 — Planos são dados duráveis, não texto de conversa

**Status:** Aceito · 2026-09-18

## Contexto

O pipeline de orquestração tem classificador de tarefa, seleção de agentes e um
preflight de 9 passos para alterar projetos ([Orquestração §2](../agents/orchestration.md#2-pipeline),
[Auto-modificação §6](../process/self-modification.md#6-preflight-obrigatório)). Falta
o passo anterior para qualquer pedido longo — "quero fazer X" — virar plano:
subtarefas, dependências, critérios de conclusão. E falta onde esse plano vive:
o WSL cai sem aviso ([R2](../architecture/windows-wsl.md#r2--o-wsl-é-derrubado-por-fora)),
e uma tarefa de 40 minutos não pode recomeçar do zero nem ser esquecida.

## Alternativas

| Alternativa | Por que não |
|---|---|
| O modelo mantém o plano no contexto | Some com o contexto, não sobrevive à queda, não é auditável |
| Motor de workflow externo (Temporal etc.) | Infraestrutura desproporcional para um assistente pessoal |
| **Planner produz um `Plan` estruturado; `TaskStore` em SQLite guarda estado e eventos** | — |

## Decisão

1. **Planner** (agente de planejamento, `zordon-agents`): transforma o pedido em
   `Plan` — objetivo, subtarefas com dependências (DAG), capacidade exigida por
   subtarefa, critério de conclusão verificável e orçamento. O plano é
   **comunicado** antes de executar quando altera algo
   ([ADR-0014](ADR-0014-nenhuma-iniciativa-silenciosa.md)).
2. **State Store** (`TaskStore`, SQLite, mesmo banco da memória): cada tarefa e
   subtarefa tem máquina de estados persistida — `planned`, `ready`, `running`,
   `waiting_approval`, `blocked`, `verifying`, `done`, `failed`, `cancelled` — com
   cada transição registrada. Resultado de subtarefa é gravado antes de a
   próxima começar.
3. **Retomada**: ao subir, o núcleo lê as tarefas `running` e as leva a
   `blocked` com motivo "o núcleo reiniciou"; o usuário (ou a política da tarefa)
   decide retomar. Nada é reexecutado sozinho se tinha efeito colateral.

Detalhes em [Planner e estado de tarefas](../specs/agents/planner.md).

## Consequências

- O Orchestrator executa subtarefas `ready` do plano, não "o que o modelo lembrar".
- O Workflow Engine ([Automação §6](../specs/automation/design.md#6-workflowengine))
  usa o mesmo `TaskStore` para pausa, retomada e retry.
- O Voice Narrator narra mudanças de estado do plano, não cada ferramenta
  ([SPEC-012](../specs/voice/SPEC-012-voice-first-narracao-e-estados.md)).
