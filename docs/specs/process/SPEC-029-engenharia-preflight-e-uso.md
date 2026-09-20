---
document: spec-029
module: process
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,engenharia,preflight,auto-modificacao,tokens,m8]
specId: SPEC-029
---

# SPEC-029 — Perfil de engenharia, preflight e uso de tokens

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `ArchitectureAgent` |
| **Revisores** | SecurityAgent, SpecAgent |
| **Marco** | M8 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

O Zordon passa a **pensar sobre mudar o próprio projeto do jeito certo**: agentes
de engenharia, o preflight de nove passos escrito e registrado, e a conta de
quanto tudo isso custa em tokens
([Auto-modificação](../../process/self-modification.md),
[Catálogo de agentes](../../agents/catalog.md)).

## 2. Problema

Não existe perfil de engenharia, o preflight obrigatório vive só na
documentação, e ninguém sabe quantos tokens o dia consumiu.

## 3. Escopo

**Perfil de engenharia** (agentes embutidos, `profile = "engineering"`)

| Agente | Teto | O que faz |
|---|---|---|
| `spec` | GREEN | Escreve e revisa SPEC e ADR (texto, não código) |
| `architecture` | GREEN | Impacto arquitetural, duplicação, limites de módulo |
| `java` | YELLOW | Código Java do projeto: lê, propõe diff, roda build e teste |
| `testing` | YELLOW | Testes: cobertura por critério de aceite, casos golden |
| `codereview` | GREEN | Revisa diff: invariantes, segurança, estilo do projeto |
| `documentation` | YELLOW | Mantém a documentação coerente com o que mudou |

- Todos têm `rag.search` fixado: o passo 2 do preflight é a primeira coisa que
  fazem.
- Nenhum deles escreve em caminho de instalação (a política já proíbe).

**Preflight de nove passos (`change.plan`)**

Antes de qualquer alteração de projeto, o Zordon monta e **registra** o plano,
com os nove passos da [Auto-modificação §6](../../process/self-modification.md#6-preflight-obrigatório):

1. tarefa e escopo; 2. consulta ao RAG; 3. SPECs e ADRs relacionados;
4. precisa de SPEC nova?; 5. impacto arquitetural e de segurança (**toca o
núcleo de confiança?**); 6. agentes escolhidos; 7. plano de execução;
8. orçamento de tokens; 9. **comunicar ao usuário**.

- O resultado é uma tarefa da SPEC-023 (origem `change:<id>`), com uma etapa por
  passo, e uma notificação com o resumo.
- O plano **não executa nada**: ele termina em `waiting_human`, esperando a
  decisão do dono.
- Passo 5 usa a lista do núcleo de confiança; tocar nele marca o plano como
  "só por PR" e o passo 9 vira obrigatório com espera.

**Núcleo de confiança verificado**
- `zordon-security` e `zordon-defense` não dependem de `zordon-core`.
- `zordon-api` não depende de ninguém.
- Regras de ArchUnit no `verifyAll`, junto das que já existem
  (`ProcessBuilder` só em `zordon-security`).

**Uso de tokens (`TokenUsageService`)**
- Soma por dia, por provider, por modelo e por ator (turno, agente, automação,
  destilação), a partir do que cada resposta já informa.
- Guarda em `usage_daily` (V008) — sobrevive a reinício.
- `usage.summary {days?}` no ZWP e `system.diagnostics.usage`.
- A tela mostra o dia e os sete dias.

## 4. Não escopo

- **Executar a mudança** (`ChangeExecutor`): escrever o código, rodar o
  `verifyAll` e **commitar** dependem de sandbox (ADR-0031) e de uma decisão do
  dono sobre o Zordon commitar no repositório dele. Fica registrado como o
  próximo passo, com o preflight já pronto para alimentá-lo.
- Evaluation Engine com conjuntos em `evals/` e troca de modelo guiada por eles.
- `KnowledgeGraph` e `ContextRouter` por escopo.
- Os agentes de engenharia restantes do catálogo (javafx, security, defense,
  devops, mcp, rag, voice, database): o molde é o mesmo arquivo TOML.

## 5. Arquitetura

```text
change.plan {goal} ─► Preflight (9 passos) ─┬─ rag.search (passo 2 e 3)
                                            ├─ trust core? (passo 5)
                                            ├─ agentes (passo 6)
                                            └─ TaskStore + notificação (passo 9)

AI_RESPONSE / AgentRunner ─► TokenUsage ─► usage_daily (V008) ─► usage.summary
```

## 6. Fluxo

"Zordon, planeje a mudança: adicionar uma tela para MCP"
1. `change.plan` monta os nove passos, consultando o RAG nos passos 2 e 3.
2. O passo 5 diz que a mudança **não** toca o núcleo de confiança.
3. O passo 6 escolhe `spec`, `javafx` (quando existir), `testing` e
   `documentation`.
4. O passo 8 registra o orçamento.
5. O passo 9 notifica: "Plano pronto, esperando você".

## 7. Interfaces

```java
public final class Preflight {
    record Step(String id, String title, String finding);
    record Plan(String taskId, List<Step> steps, boolean touchesTrustCore, long tokenBudget);
    Plan plan(String goal);
}
public interface UsageStore {
    void recordUsage(LocalDate day, String provider, String model, String actor, long input, long output);
    Map<String, Object> usageSummary(int days);
}
```

## 8. Eventos

Nenhum novo: o plano aparece como `TASK_STATE` e como notificação.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Uso diário | `zordon.db`: `usage_daily` (V008) | Permanente |
| Planos de mudança | Tarefas da SPEC-023 | Permanente |

## 10. Segurança

- O preflight **não executa**: ele lê documentação e escreve um plano.
- Tocar o núcleo de confiança não é negado pelo plano — é **marcado**, e a
  execução continua sendo por PR humano (ADR-0024).
- Os agentes de engenharia obedecem ao mesmo motor de permissão: `java` e
  `testing` são YELLOW e pedem confirmação para escrever.

## 11. Permissões

- `change.plan`: GREEN (só leitura e escrita no banco do Zordon).
- Os agentes seguem o teto do perfil.

## 12. Observabilidade

- `system.diagnostics.usage` com o dia e os sete dias.
- Log INFO por plano, com os passos resolvidos.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Sem índice de RAG | O passo 2 registra "sem índice" e o plano segue, marcado |
| Sem modelo disponível | O plano falha com o motivo; nada é gravado pela metade |
| Objetivo vago | O passo 1 pede o que falta, e o plano fica `waiting_human` |

## 14. Testes

- Preflight: os nove passos aparecem, na ordem, com a consulta ao RAG citada; o
  plano fica `waiting_human`; tocar o núcleo de confiança é marcado.
- Uso: soma por dia e ator, sobrevive a reinício, e o resumo de sete dias.
- ArchUnit: `zordon-security` e `zordon-defense` sem `zordon-core`.
- Agentes de engenharia: carregam, têm `rag.search` fixado e o teto declarado.

### Evidências (2026-09-19)

- `PreflightTest` (4 testes):
  - CA-1: "planeje a mudança: adicionar uma tela para MCP" vira `change.plan`;
    os nove passos são gravados como tarefa `change:preflight`, o passo 2 cita a
    SPEC-020 encontrada pelo RAG, o passo 3 cita o arquivo, o passo 6 inclui o
    `codereview`, e a tarefa termina em `waiting_human` — nada é executado;
  - CA-2: "mudar o motor de permissão…" vem marcado como núcleo de confiança,
    com "só por PR humano" e notificação HIGH;
  - CA-3: o uso soma por dia e por ator (turno e agente), ignora fragmento de
    streaming, e sobrevive ao reinício do banco;
  - CA-5: os seis agentes de engenharia existem, com teto declarado e
    `rag.search` fixado; o `rag` não alcança `fs.write`.
- **No serviço instalado (2026-09-19, 22h14), sobre o projeto real:**
  `change.plan {goal: "adicionar uma tela para MCP"}` gravou a tarefa
  `task_mu94j9p2kg4m` com os nove passos; o passo 2 citou
  `orchestration.md › 3. Exemplo` e `design.md › 7. Adicionando capacidade sem
  tocar o núcleo`; o passo 3 achou `docs/specs/mcp/design.md`, o ADR-0012 e o
  ADR-0034; o passo 5 disse que a mudança fica fora do núcleo de confiança; e a
  tarefa ficou esperando o dono, sem nada ter sido alterado.
- CA-4: `ArchitectureTest` ganhou duas regras no `verifyAll` —
  `zordon-security` não depende de `core`, `memory`, `defense` nem `ai`; e
  `zordon-defense` não depende de `core`, `memory` nem `ai`.

## 15. Critérios de aceite

- `CA-1` Dado "planeje a mudança: X", então os nove passos são registrados como
  tarefa, o passo 2 cita a documentação encontrada, e a tarefa termina em
  `waiting_human` sem executar nada.
- `CA-2` Dada uma mudança que toca o núcleo de confiança, então o plano vem
  marcado e diz que só entra por PR humano.
- `CA-3` Dados turnos e execuções de agente, então o uso de tokens é somado por
  dia e por ator e sobrevive ao reinício.
- `CA-4` Dado o `verifyAll`, então as regras de ArchUnit provam que o núcleo de
  confiança não depende do núcleo de aplicação.
- `CA-5` Dados os agentes de engenharia, então eles existem com teto declarado e
  `rag.search` fixado.

## 16. Impacto em outros módulos

- `zordon-memory`: migração V008 (`usage_daily`).
- `zordon-core`: pacote `change` (preflight), `usage`, agentes embutidos novos,
  métodos ZWP.
- `zordon-desktop`: uso de tokens e plano na tela.

## 17. Dependências

- [Auto-modificação](../../process/self-modification.md) ·
  [SPEC-028](../rag/SPEC-028-base-de-conhecimento.md) ·
  [SPEC-023](../agents/SPEC-023-planos-duraveis-e-verificacao.md) ·
  [ADR-0024](../../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md)
