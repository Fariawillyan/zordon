---
document: spec-agents-evaluation
module: agents
section: evaluation
version: 1
updatedAt: 2026-09-18
securityLevel: internal
tags: [avaliacao,verificacao,verifier,qualidade,eval,metricas]
specId: null
---

# Avaliação e verificação

Como o Zordon sabe que uma tarefa foi concluída de verdade, e como mede, ao
longo do tempo, se agentes e modelos acertam. Decisão em
[ADR-0036](../../adr/ADR-0036-conclusao-verificada.md).

## 1. Duas coisas diferentes

| | Verifier | Evaluation Engine |
|---|---|---|
| Pergunta | Esta tarefa, agora, está concluída? | Este agente/modelo acerta este tipo de tarefa? |
| Quando | Ao fim de cada etapa | Continuamente, e antes de trocar modelo ou prompt |
| Saída | Veredito da etapa | Taxa de acerto por capacidade, custo, tempo |

## 2. Critério de conclusão

Todo `doneWhen` do [Planner](planner.md) é de um destes tipos:

| Tipo | Exemplo | Quem verifica |
|---|---|---|
| Comando | `gradle test` passa no sandbox | Execução no [Sandbox](../../security/sandbox.md) |
| Estado observável | Container `api` rodando; arquivo com hash X | Ferramenta de leitura |
| Fonte | Toda afirmação cita um trecho que existe no RAG | Checagem contra o índice |
| Julgamento | "A explicação está correta" | Verifier com outro modelo |
| Humano | Não verificável automaticamente | Confirmação na tela |

Critério do tipo julgamento nunca é o único numa etapa com efeito.

## 3. Verifier

- É outro agente, com outro contexto; quando há mais de um modelo com a
  capacidade, **outro modelo** ([Capacidades](../core/capabilities-and-routing.md)).
- Recebe o critério, as evidências (saídas, diffs, relatórios) e o pedido
  original — **não** a narrativa do executor, que tende a convencer.
- Veredito: `pass`, `fail` com motivo, ou `inconclusive` (vai para humano).
- O perfil de engenharia já faz isso no ciclo de revisão
  ([Orquestração §6](../../agents/orchestration.md#6-ciclo-de-revisão)); esta regra
  estende a todo plano.

## 4. Evaluation Engine

- Registra cada veredito: tarefa, etapa, agente, modelo, capacidade, resultado,
  custo, tempo. Mesma base do [TaskStore](planner.md#4-taskstore).
- Mantém **conjuntos de avaliação** por capacidade — casos dourados com entrada e
  critério — em `evals/<capacidade>/`, versionados no git.
- Antes de trocar o modelo de um papel ou o prompt de um agente, o conjunto da
  capacidade roda; a troca só passa se não piorar a taxa de acerto além da
  tolerância declarada.
- Alimenta o [roteador](../core/capabilities-and-routing.md#2-o-roteador): qualidade
  medida é preferência, não palpite.

## 5. Voz

O narrador só diz "concluído" depois de `pass`. `fail` vira estado `error` e a
frase do motivo; `inconclusive` vira `attention` e "Preciso que você confira o
resultado." ([SPEC-012](../voice/SPEC-012-voice-first-narracao-e-estados.md)).

## 6. Métricas

| Métrica | Onde |
|---|---|
| Taxa de acerto por capacidade e por modelo | Tela Uso (M8) e trace |
| Etapas `inconclusive` por semana | Diagnóstico |
| Custo por tarefa concluída | [Uso de tokens](../../operations/token-usage.md) |

## 7. Marco

Verifier no M5, com o Planner. Conjuntos de avaliação e troca de modelo guiada
por eles no M8, com a plataforma de desenvolvimento.
