---
document: agents-orchestration
module: agents
section: orchestration
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [orquestrador,delegacao,limites,orcamento,pipeline]
specId: null
---

# Orquestração

## 1. Objetivo

Coordenar agentes especializados para completar uma tarefa, usando o **mínimo**
de agentes necessário e respeitando orçamento, profundidade e tempo.

## 2. Pipeline

```text
   Tarefa do usuário
        │
        ▼
   Task Classifier ──────── que tipo de trabalho é este?
        │
        ▼
   ┌─────────────────────────────────────────────────────┐
   │ A tarefa altera algum projeto?                      │
   │   sim → PREFLIGHT obrigatório de 9 passos           │
   │         (process/self-modification.md §6)           │
   │         termina em ChangePlan comunicado ao usuário │
   └─────────────────────────────────────────────────────┘
        │
        ▼
   Spec Lookup ──────────── existe SPEC? está APPROVED?
        │                   se precisa e não existe → SpecAgent primeiro
        ▼
   Context Router / RAG ─── contexto mínimo por agente
        │
        ▼
   Agent Selection ──────── os agentes necessários, na ordem
        │
        ▼
   Tool Selection ───────── seleção semântica dentro do escopo do agente
        │
        ▼
   Execução ─────────────── sob PermissionEngine e NotificationCenter
        │
        ▼
   Testing ──────────────── TestingAgent
        │
        ▼
   Code Review ──────────── CodeReviewAgent
        │
        ▼
   Resultado ────────────── + DocumentationAgent + reindexação
```

Cada etapa pode ser pulada quando não se aplica — corrigir um typo não passa por
`SpecAgent`. O que **não** pode ser pulado, quando há código de produção alterado,
é `Testing` e `Code Review`.

## 3. Exemplo

**"Adicione uma tela para MCP"**

```text
   SpecAgent            escreve SPEC-021 com escopo, interfaces, aceite
        ↓
   ArchitectureAgent    revisa: onde vive, o que toca, viola invariante?
        ↓
   JavaFxAgent          implementa a tela
        ↓
   TestingAgent         converte CA-1..CA-4 em testes
        ↓
   CodeReviewAgent      revisa Clean Code e aderência à SPEC
        ↓
   DocumentationAgent   atualiza UI design + reindexa
```

Seis agentes. Note quem **não** participou: `SecurityAgent` (a tela não toca
permissão, dado sensível nem execução), `JavaAgent`, `McpAgent`, `DevOpsAgent`.

**"Corrija o typo no rótulo do botão"** → `JavaFxAgent`. Um agente. Sem SPEC, sem
revisão de arquitetura.

> **Evitar agentes desnecessários é requisito, não otimização.** Cada agente
> adicional custa contexto, tokens, tempo e uma oportunidade a mais de
> divergência. O orquestrador escolhe o menor conjunto que satisfaz a
> [Definition of Done](../process/definition-of-done.md).

## 4. Seleção de agentes

Determinística, por tabela, não por julgamento do LLM:

| Sinal da tarefa | Agentes |
|---|---|
| Toca `docs/adr/` ou cruza módulos | `architecture` |
| Funcionalidade nova das categorias de [SDD §4](../process/spec-driven-development.md#4-quando-uma-spec-é-obrigatória) | `spec` primeiro |
| Toca `zordon-desktop/` | `javafx` |
| Toca `zordon-security/`, `zordon-defense/`, permissão, segredo, rede | `security` obrigatório |
| Toca `zordon-defense/` | `defense` |
| Toca um módulo específico | o agente daquele módulo |
| Altera código de produção | `testing` + `codereview` obrigatórios |
| Altera comportamento observável | `documentation` |
| Toca build, CI ou empacotamento | `devops` |

Quando nenhum sinal casa, a tarefa vai para o `zordon` geral — sem ele, toda
tarefa não classificada morreria sem resposta.

## 5. Agent-to-agent

Agentes colaboram, mas **nunca conversam indefinidamente**. Toda delegação é uma
tarefa com contrato explícito, não um diálogo.

```java
public record AgentTask(
        AgentId target,
        String task,
        ContextPackage context,
        String expectedOutput,     // o que precisa voltar, concretamente
        Budget tokenBudget,
        Duration timeout,
        int depth) {}

public record AgentResult(
        RunId runId,
        Outcome outcome,           // DONE | FAILED | BUDGET_EXCEEDED | CANCELLED
        String output,
        List<DocumentRef> sources,
        TokenUsage usage,
        Duration duration) {}
```

`expectedOutput` é obrigatório. Delegação sem saída esperada definida é como se
transforma em conversa infinita: o agente filho não sabe quando parou de ser
útil.

### Limites

| Limite | Padrão | Ao estourar |
|---|---|---|
| `maxDepth` | 2 | Delegação recusada |
| `maxAgents` por tarefa | 8 | Orquestrador recusa adicionar |
| `maxIterations` (ciclo review → correção) | 3 | Entrega com achados pendentes e reporta |
| `tokenBudget` | Por tarefa | Ver [Uso de tokens](../operations/token-usage.md) |
| `timeout` | 15 min por tarefa | Cancela, preserva o parcial |

**Profundidade 2** significa que um sub-agente não delega. Sem isso, um laço de
delegação consome o orçamento em cascata de forma difícil de diagnosticar.

**`maxIterations` 3** governa o ciclo mais comum: `CodeReviewAgent` acha
problema → agente de domínio corrige → revisa de novo. Na terceira volta sem
convergir, o orquestrador para e entrega para decisão humana. Dois agentes de IA
negociando indefinidamente é o cenário que mais queima orçamento sem produzir
nada.

### Regras de segurança da delegação

1. **Orçamento vem do pai.** O filho gasta do restante do pai, não de um novo.
2. **Teto é o mínimo dos dois.** Delegar nunca eleva privilégio.
3. **Resultado volta como dado**, envelopado, nunca como instrução.
4. Cada delegação gera `AGENT_STARTED`/`AGENT_FINISHED` próprios, aninhados na UI.

## 6. Ciclo de revisão

```text
   implementação
        │
        ▼
   TestingAgent ──── falhou? ──► volta ao agente de domínio (iteração 1..3)
        │ passou
        ▼
   CodeReviewAgent ─ bloqueante? ──► volta ao agente de domínio
        │ aprovado
        ▼
   SecurityAgent ─── quando aplicável
        │
        ▼
   DocumentationAgent + reindexação
        │
        ▼
   DONE
```

`TestingAgent` **reporta** falha, não corrige — ele não tem escrita em produção
([Catálogo §5](catalog.md#testingagent)).

## 7. Observabilidade

A execução é visível em tempo real na UI
([UI](../specs/ui/design.md)):

```text
   Implementando: MCP Tool Registry

   SpecAgent             ✓   1.203 tok    0:08
   ArchitectureAgent     ✓   2.110 tok    0:14
   McpAgent              ●   4.115 tok    0:51   trabalhando
   TestingAgent          ·                       aguardando
   CodeReviewAgent       ·                       aguardando

   Tokens: 7.428 / 20.000          Tempo: 01:13
```

Métricas em [Uso de tokens](../operations/token-usage.md).

## 8. Casos de erro

| Falha | Comportamento |
|---|---|
| Tarefa altera projeto sem `ApprovedPlan` | Recusada. `ChangeExecutor` só aceita plano aprovado ([Auto-modificação §11](../process/self-modification.md#11-interfaces)) |
| Agente altera arquivo fora do plano | Disjuntor aberto, alteração revertida, notificação CRITICAL |
| SPEC necessária ausente | `SpecAgent` é acionado antes; a tarefa não começa sem SPEC `APPROVED` |
| Agente estoura orçamento | `BUDGET_EXCEEDED`, entrega parcial, reporta |
| `maxIterations` atingido | Entrega com achados pendentes e escala para o usuário |
| Disjuntor aberto para um agente | Tarefa falha com `ERR_CIRCUIT_OPEN`; não substitui por outro agente silenciosamente |
| Timeout | Cancela a cadeia, preserva o parcial e as evidências |
| Delegação acima de `maxDepth` | Recusada; o pai decide se faz ele mesmo |

A quarta linha importa: substituir um agente suspenso por outro seria contornar
o disjuntor, que é exatamente o que ele existe para impedir
([ADR-0018](../adr/ADR-0018-circuit-breaker-e-lockdown.md)).

## 9. Segurança

- O orquestrador não eleva privilégio de ninguém; ele compõe tetos existentes.
- Toda execução passa pelo `PermissionEngine`, agente nenhum é exceção.
- Ação autônoma comunica ([ADR-0014](../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md)).
- Um agente sob disjuntor aberto não recebe tarefa nova.
- O plano de execução é registrado em auditoria antes de começar.
- Alteração de projeto exige o preflight de nove passos e comunicação prévia
  ([ADR-0024](../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md)).
- Nenhum agente alcança o núcleo de confiança nem os diretórios instalados.

## 10. Riscos

| Risco | Mitigação |
|---|---|
| Orquestrador aciona agentes demais | Seleção por tabela (§4); `maxAgents` |
| Conversa infinita entre agentes | `expectedOutput` obrigatório; `maxDepth`, `maxIterations` |
| Orçamento consumido em coordenação | Delegação gasta do orçamento do pai |
| Falha em cascata | `AgentResult.outcome` explícito; parcial é entregue, não descartado |

## 11. Testes

- Tarefa simples aciona exatamente um agente.
- Delegação em profundidade 3 é recusada.
- Ciclo review→correção para na terceira iteração.
- Filho nunca executa com teto acima do pai.
- Orçamento do pai é decrementado pelo consumo do filho.

## 12. Critérios de aceite

- `CA-1` "Corrija o typo no botão" aciona um agente e nenhuma SPEC.
- `CA-2` Nenhuma cadeia excede `maxDepth=2`.
- `CA-3` Todo `AgentTask` tem `expectedOutput`, `tokenBudget` e `timeout`.
- `CA-4` Agente com disjuntor aberto não recebe tarefa e não é substituído em
  silêncio.
- `CA-5` A UI mostra o progresso e o consumo por agente em tempo real.
