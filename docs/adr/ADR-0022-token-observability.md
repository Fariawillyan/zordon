---
document: adr-0022
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,tokens,custo,orcamento]
specId: null
---

# ADR-0022 — Todo consumo de token é medido, orçado e atribuído

**Status:** Aceito · 2026-09-17

## Contexto

Token é o insumo que o Zordon consome e a única coisa que ele gasta de forma
difícil de perceber. Um laço de agente mal comportado queima dinheiro em silêncio,
e com agentes especializados colaborando entre si, o consumo de uma única tarefa
passa por vários modelos e várias chamadas.

Sem atribuição, "a conta veio alta" é um fato sem ação possível.

## Decisão

Todo consumo é registrado em `TokenUsageService`, atribuído a **tarefa, agente,
modelo e provider**, com custo e duração.

### Exato nunca se confunde com estimado

```java
public enum Accuracy { EXACT, ESTIMATED }
```

| Origem | `accuracy` |
|---|---|
| `usage` devolvido pela API (Anthropic, servidores OpenAI-compatíveis) | `EXACT` |
| Contagem por tokenizador aproximado (modelo local sem `usage`) | `ESTIMATED` |
| Requisição cancelada no meio | `ESTIMATED` |

Na interface, estimado aparece marcado (`~5.370 tokens (estimado)`), e um agregado
que mistura os dois é marcado estimado por inteiro. **Nunca apresentar estimativa
como valor exato** — um número que parece preciso e não é corrompe toda decisão
tomada sobre ele.

### Orçamento com degradação graciosa

Orçamento por tarefa, por agente, por dia e por mês. Ao aproximar-se do teto, o
orquestrador aciona alavancas em ordem de custo em qualidade: cache de prompt →
reduzir contexto RAG → resumir histórico → evitar agente adicional → modelo menor.

**Duas coisas nunca são alavanca de economia:**

1. Contexto obrigatório de segurança. Se a tabela de classificação de risco não
   cabe no orçamento, a tarefa não roda — cortá-la para caber é o tipo de
   otimização que produz incidente.
2. `TestingAgent` e `CodeReviewAgent`, quando há código de produção alterado.
   Economizar token desligando verificação é trocar dinheiro por defeito.

Ao estourar: **entrega o parcial**, nunca descarta o trabalho feito.

### Visível em tempo real

Consumo por agente durante a execução e por tarefa ao final, mais a tela
`Zordon > Usage` com hoje, 7 e 30 dias por agente, modelo, tarefa e projeto.

A visibilidade é o que transforma otimização em algo acionável: sem ver que o
`CodeReviewAgent` consome 28.400 tokens/dia, ninguém pensa em ajustar o contexto
que ele recebe.

## Consequências

**Positivas.** "Por que esta tarefa custou tanto?" tem resposta em uma tela.
Regressão de custo — tipicamente um invalidador de cache de prompt introduzido
sem querer — aparece como métrica em vez de aparecer na fatura. Orçamento com
degradação evita tanto o estouro quanto o corte cego.

**Negativas.** Instrumentação em todo caminho de chamada. Preço é dado que
desatualiza (vive em `~/.zordon/pricing.toml`, versionado, não constante no
código). Estimativa em modelos locais nunca será precisa.

**Privacidade:** o registro contém contagens, modelo, agente e duração —
**nunca conteúdo** de prompt ou resposta. E nenhuma telemetria sai da máquina.

## Relação com outros ADRs

O cache de prompt é a maior alavanca e depende inteiramente da estabilidade do
prefixo — por isso o [Context Router](../rag/context-router.md) ordena chunks
deterministicamente e não recalcula contexto entre voltas do laço. As três
decisões — [ADR-0010](ADR-0010-selecao-semantica-de-tools.md) (ferramentas),
[ADR-0020](ADR-0020-rag-como-conhecimento.md) (documentos) e esta — atacam o
mesmo problema em camadas diferentes.
