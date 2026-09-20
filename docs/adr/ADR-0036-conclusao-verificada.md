---
document: adr-0036
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,avaliacao,verificacao,agentes,qualidade]
specId: null
---

# ADR-0036 — Conclusão é verificada, não declarada

**Status:** Aceito · 2026-09-18

## Contexto

Um agente que diz "pronto" pode estar errado: o teste que "passou" não rodou, o
arquivo "corrigido" é outro, a resposta "certa" inventou a fonte. O perfil de
engenharia já tem ciclo de revisão ([Orquestração §6](../agents/orchestration.md#6-ciclo-de-revisão)),
e a Definition of Done tem portões automáticos ([DoD](../process/definition-of-done.md)).
Falta uma regra geral para toda tarefa, e falta medir, com o tempo, se agentes e
modelos acertam — o Model Router precisa disso para escolher por qualidade
([ADR-0033](ADR-0033-capacidades-e-roteador-de-modelos.md)).

## Alternativas

| Alternativa | Por que não |
|---|---|
| Confiar no agente que executou | É ele quem tem incentivo a declarar sucesso |
| Revisão humana de tudo | O Zordon existe para poupar isso |
| **Critério de conclusão verificável por subtarefa + verificador independente + registro de resultado** | — |

## Decisão

1. Toda subtarefa do Planner ([ADR-0035](ADR-0035-planos-duraveis-e-estado-de-tarefas.md))
   nasce com critério de conclusão **verificável**: um comando que passa (no
   sandbox), um estado observável (container rodando, arquivo com hash), uma
   checagem de fonte (citação que existe no RAG). Critério não verificável é
   marcado e vai para confirmação humana.
2. O **Verifier** é outro agente, com outro contexto e, quando possível, outro
   modelo; ele recebe o critério e as evidências, não a narrativa do executor.
3. O **Evaluation Engine** registra cada veredito — tarefa, agente, modelo,
   capacidade, passou ou não, custo, tempo — e mantém conjuntos de avaliação
   (casos dourados por capacidade) que rodam antes de trocar modelo ou prompt.

Detalhes em [Avaliação e verificação](../specs/agents/evaluation.md).

## Consequências

- "Concluído" no estado da tarefa exige veredito do Verifier; sem ele, a tarefa
  fica em `verifying`.
- O Voice Narrator só fala "concluído" depois do veredito.
- Trocar o modelo de um papel exige passar o conjunto de avaliação da capacidade
  ([Core §7](../specs/core/design.md) já pedia avaliação antes de mudar o padrão).
