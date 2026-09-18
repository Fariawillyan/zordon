---
document: ops-token-usage
module: operations
section: tokens
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [tokens,custo,orcamento,dashboard,otimizacao]
specId: null
---

# Uso de tokens

## 1. Objetivo

Tornar o consumo de tokens **observável, orçado e otimizável** — por tarefa, por
agente, por modelo e por dia.

## 2. Contexto

O Zordon é construído e operado por agentes. Token é o insumo que ele consome e a
única coisa que ele gasta de forma difícil de perceber: um laço de agente mal
comportado queima dinheiro sem barulho.

Medir não é contabilidade — é o que permite decidir onde otimizar. As duas
perguntas que esta instrumentação precisa responder são "por que esta tarefa
custou tanto?" e "onde está o desperdício?".

## 3. `TokenUsageService`

```java
public interface TokenUsageService {
    void record(TokenUsage usage);
    TaskUsage byTask(TaskId task);
    List<AgentUsage> byAgent(Instant since);
    UsageSummary summary(Period period);
}

public record TokenUsage(
        TaskId  taskId,
        RunId   runId,
        AgentId agent,
        String  provider,
        String  model,
        long    inputTokens,
        long    cachedTokens,      // lidos do cache de prompt
        long    outputTokens,
        long    totalTokens,
        Money   cost,
        Duration duration,
        Accuracy accuracy) {}      // EXACT | ESTIMATED

public enum Accuracy { EXACT, ESTIMATED }
```

## 4. Exato vs estimado

**Nunca apresentar estimativa como valor exato.**

| Provider | Origem da contagem | `accuracy` |
|---|---|---|
| Anthropic | `usage` da resposta da API | `EXACT` |
| Compatível com OpenAI (qualquer servidor) | `usage`, pedido com `stream_options.include_usage` | `EXACT` |
| Servidor que não devolve `usage` | Quatro caracteres por token, marcado com `≈` na tela ([SPEC-004](../specs/core/SPEC-004-providers-configuraveis.md)) | `ESTIMATED` |
| Requisição cancelada no meio | Parcial medido + estimativa do restante | `ESTIMATED` |

Na interface, valor estimado aparece com marcação visível:

```text
Total: ~5.370 tokens (estimado)
Total:  5.370 tokens
```

Agregado que mistura exato e estimado é marcado como estimado por inteiro. Um
número que parece preciso e não é, corrompe toda decisão tomada sobre ele.

## 5. Orçamento por tarefa

```text
   Tarefa recebe orçamento (padrão 15.000 tokens)
        │
        ├── consumo em 70% ──► orquestrador começa a economizar
        │                       (modelo menor, menos contexto, menos agentes)
        │
        ├── consumo em 90% ──► sem agentes adicionais; encerra o que está aberto
        │
        └── 100% ───────────► BUDGET_EXCEEDED; entrega parcial, não descarta
```

Alavancas que o orquestrador aciona, em ordem de preferência:

| Alavanca | Custo em qualidade |
|---|---|
| Aproveitar cache de prompt | Nenhum |
| Reduzir contexto do RAG | Baixo, se o router estiver bom |
| Resumir histórico | Baixo |
| Evitar agente adicional | Médio — pode pular revisão opcional |
| Usar modelo menor | Médio a alto |

**O que ele nunca faz:** cortar o contexto obrigatório de segurança
([Context Router §10](../rag/context-router.md#10-casos-de-erro)) nem pular
`TestingAgent`/`CodeReviewAgent` quando há código de produção alterado. Economizar
tokens desligando verificação é trocar dinheiro por defeito.

Orçamentos padrão:

| Escopo | Padrão |
|---|---|
| Tarefa simples | 15.000 |
| Tarefa com SPEC | 40.000 |
| Execução de agente | 20.000 |
| Turno de conversa | 30.000 |
| Dia | US$ 10,00 |
| Mês | US$ 100,00 |

## 6. Na interface

Por agente, durante a execução:

```text
   DeveloperAgent

   Entrada    4.230      (cache: 3.100)
   Saída      1.140
   Total      5.370

   Tempo      13,4 s
   Modelo     claude-opus-5
```

Por tarefa:

```text
   TAREFA   Implementar MCP Registry

   Agente                 Tokens      Tempo
   ─────────────────────────────────────────
   SpecAgent               1.203       0:08
   ArchitectureAgent       2.110       0:14
   JavaAgent               5.820       1:47
   TestingAgent            2.004       0:33
   CodeReviewAgent           978       0:12
   ─────────────────────────────────────────
   TOTAL                  12.115       2:54
   Orçamento              20.000        61%
   Custo                  US$ 0,14
```

## 7. `Zordon > Usage`

```text
┌──────────────────────────────────────────────────────────────┐
│ USO                                          hoje · 7d · 30d │
├──────────────────────────────────────────────────────────────┤
│ TOKENS HOJE                                                  │
│  Entrada    142.300     Cache     98.100   (69% de acerto)   │
│  Saída       31.420     Total    173.720                     │
│  Custo      US$ 1,84    de 10,00   ████░░░░░░░░░░░░          │
├──────────────────────────────────────────────────────────────┤
│ POR AGENTE                     tokens    custo    chamadas   │
│  JavaAgent                     61.200    0,74          18    │
│  CodeReviewAgent               28.400    0,31          22    │
│  SpecAgent                     19.100    0,22           9    │
│  system (assistente)           12.800    0,14          38    │
├──────────────────────────────────────────────────────────────┤
│ POR MODELO                     tokens    custo    p50        │
│  claude-opus-5                104.200    1,62    1,9 s       │
│  claude-haiku-4-5              69.520    0,22    0,4 s       │
├──────────────────────────────────────────────────────────────┤
│ POR TAREFA (últimas)                                         │
│  Implementar MCP Registry      12.115    0,14    ✓           │
│  Corrigir rótulo do botão         840    0,01    ✓           │
│  Investigar queda da API        8.302    0,09    ✓           │
├──────────────────────────────────────────────────────────────┤
│ POR PROJETO                                                  │
│  zordon                       148.900    1,71                │
│  aurora                        24.820    0,13                │
└──────────────────────────────────────────────────────────────┘
```

Histórico retido por 90 dias, exportável em CSV.

## 8. Otimização

Em ordem de retorno — a mesma ordem de
[Core §7](../specs/core/design.md#7-orçamento-e-custo), aplicada ao trabalho de
agentes:

1. **Cache de prompt.** Maior ganho, custo zero em qualidade. Depende inteiramente
   da estabilidade do prefixo — por isso o Context Router ordena chunks
   deterministicamente ([§7](../rag/context-router.md#7-interação-com-o-cache-de-prompt)).
2. **RAG em vez de documentação inteira.** ~120.000 → ~7.000 tokens por tarefa.
3. **Seleção dinâmica de ferramentas.** Nunca enviar 100 ferramentas quando 3
   bastam ([ADR-0010](../adr/ADR-0010-selecao-semantica-de-tools.md)).
4. **Seleção dinâmica de agentes.** Não acionar cinco agentes para um typo.
5. **Resumo de histórico** em vez de reenviar a conversa inteira.
6. **Deduplicação** de chunks e resultados repetidos no mesmo contexto.
7. **Esforço por papel.** Roteamento e sumarização em `low`; implementação em
   `high`.
8. **Modelo por papel.** Haiku para volume, Opus para julgamento.

`zordon.ai.cache.hit_ratio` é o termômetro. Se cai, alguém introduziu um
invalidador no prefixo — e o custo subiu silenciosamente.

## 9. Eventos

| Evento | Payload |
|---|---|
| `TOKEN_USAGE` | `{taskId, agent, model, input, cached, output, cost, accuracy}` |
| `BUDGET_WARNING` | `{taskId, used, budget, percent}` — em 70% e 90% |
| `BUDGET_EXCEEDED` | `{taskId, used, budget}` |

## 10. Segurança

- Registro de uso **não contém conteúdo** de prompt ou resposta: apenas
  contagens, modelo, agente e duração.
- Nenhuma telemetria sai da máquina
  ([Observabilidade §8](observability.md#8-o-que-não-medimos)).
- Preços vivem em `~/.zordon/pricing.toml`, dado de configuração, não constante
  no código.

## 11. Riscos

| Risco | Mitigação |
|---|---|
| Estimativa confundida com exato | `accuracy` explícito, marcação visível, agregado conservador |
| Orçamento estourado sem entrega | Parcial sempre entregue, nunca descartado |
| Economia às custas de verificação | Teste e revisão não são alavancas de economia (§5) |
| Custo cresce sem ninguém notar | Tetos diário e mensal + `BUDGET_WARNING` |
| Preço desatualizado distorce o custo | `pricing.toml` versionado; divergência sinalizada em Diagnostics |

## 12. Testes

- Toda chamada de provider produz exatamente um `TokenUsage`.
- Agregado com qualquer parcela estimada é marcado `ESTIMATED`.
- Estouro de orçamento produz entrega parcial, não exceção.
- Soma por agente confere com o total da tarefa.

## 13. Critérios de aceite

- `CA-1` Toda tarefa tem consumo registrado, com `accuracy` explícito.
- `CA-2` Nenhum valor estimado é exibido sem marcação.
- `CA-3` A UI mostra consumo por agente em tempo real durante a execução.
- `CA-4` `Zordon > Usage` mostra hoje, 7 e 30 dias por agente, modelo, tarefa e
  projeto.
- `CA-5` Atingir 100% do orçamento entrega o parcial e reporta.
