---
document: rag-context-router
module: rag
section: context-router
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [context-router,tokens,recuperacao,escopo,orcamento]
specId: null
---

# Context Router

## 1. Objetivo

Entregar a cada agente **apenas** o contexto relevante para a tarefa dele,
respeitando o escopo de segurança e o orçamento de tokens.

## 2. Problema

Enviar toda a documentação a cada chamada custaria dezenas de milhares de tokens
por volta e pioraria a qualidade da resposta. O `ContextRouter` é a peça que
transforma "o Zordon conhece a própria documentação" em algo economicamente
viável.

Ele é o análogo, para documentos, do que a
[seleção semântica](../adr/ADR-0010-selecao-semantica-de-tools.md) é para
ferramentas — e usa a mesma estrutura de dois estágios, pelo mesmo motivo.

## 3. Entrada e saída

O agente informa três coisas:

```java
public record ContextRequest(
        String  task,        // "implementar conexão MCP com descoberta de tools"
        String  module,      // "mcp"
        Intent  intent,      // IMPLEMENT | REVIEW | EXPLAIN | TEST | DESIGN | DIAGNOSE
        AgentId agent,
        int     tokenBudget) {}
```

E recebe um pacote montado, com procedência:

```java
public record ContextPackage(
        List<Chunk> chunks,
        List<DocumentRef> sources,
        int tokensUsed,
        boolean truncated) {}
```

## 4. Pipeline

```text
   ContextRequest
       │
       ▼
   ESTÁGIO 1 — filtro duro (determinístico, sem custo de modelo)
       │  escopo do agente (allowedPaths, securityScope)
       │  módulo declarado e seus dependentes diretos
       │  documentos obrigatórios por intent (§5)
       │  ~600 chunks → ~60
       ▼
   ESTÁGIO 2 — relevância
       │  BM25 sobre texto + tags + headingPath
       │  + similaridade vetorial
       │  fundidos por RRF
       │  ~60 → top-K
       ▼
   ESTÁGIO 3 — montagem sob orçamento
       │  obrigatórios primeiro, relevantes depois
       │  dedup por headingPath
       │  corta no orçamento, marca truncated
       ▼
   ContextPackage
```

O estágio 1 é **também uma barreira de segurança**: um chunk acima do
`securityScope` do agente não é filtrado depois — ele nunca entra na lista de
candidatos. O agente não sabe que ele existe.

## 5. Documentos obrigatórios por intenção

Certos documentos entram sempre, independentemente da relevância calculada,
porque a ausência deles produz erro silencioso:

| Intent | Sempre incluído |
|---|---|
| `IMPLEMENT` | A SPEC referenciada; [padrões de código](../process/code-standards.md); [DoD](../process/definition-of-done.md) |
| `DESIGN` | [Arquitetura](../architecture/overview.md); ADRs do módulo |
| `REVIEW` | [Padrões de código](../process/code-standards.md); a SPEC |
| `TEST` | [Estratégia de testes](../testing/strategy.md); critérios de aceite da SPEC |
| `DIAGNOSE` | [Windows↔WSL](../architecture/windows-wsl.md); [Observabilidade](../operations/observability.md) |
| qualquer um, para agente com escopo de segurança | [Segurança](../security/model.md) §2 (classificação de risco) |

A última linha é deliberada: um agente que pode causar efeito colateral recebe a
tabela de classificação de risco em toda tarefa, mesmo que a consulta não pareça
ter relação. Custa ~600 tokens e evita a categoria inteira de "o agente não sabia
que aquilo era arriscado".

## 6. Exemplo

Tarefa: **"Implementar conexão MCP"**, `module=mcp`, `intent=IMPLEMENT`,
agente `McpAgent`, orçamento 12.000 tokens.

```text
ESTÁGIO 1 (filtro duro)
  docs/specs/mcp/**              ✓ módulo
  docs/adr/ADR-0010*             ✓ ADR do módulo
  docs/api/**                    ✓ dependente direto
  docs/security/model.md §2      ✓ obrigatório (agente com efeito)
  docs/process/code-standards.md ✓ obrigatório (IMPLEMENT)
  docs/specs/voice/**            ✗ módulo não relacionado
  docs/specs/ui/**               ✗
  docs/operations/**             ✗

ESTÁGIO 2 (relevância) → 9 chunks

ESTÁGIO 3 (montagem)
  SPEC-014 MCP Discovery              1.840 tok   obrigatório
  MCP — design §2, §3, §6             2.310 tok
  ADR-0010 seleção semântica            980 tok
  Interfaces §4 ToolRegistry            740 tok
  Segurança §2 classificação            610 tok   obrigatório
  Padrões de código §4, §7              890 tok   obrigatório
  ─────────────────────────────────────────────
  TOTAL                               7.370 tok   de 12.000
```

Sem o router, o mesmo agente receberia ~120.000 tokens de documentação, dos quais
usaria esses mesmos 7.370.

## 7. Interação com o cache de prompt

O contexto recuperado é **semi-estável** e vai na posição correspondente da
montagem do prompt ([Core §3](../specs/core/design.md#3-composição-de-contexto)):
depois do bloco estável, antes do volátil.

Duas regras que preservam o cache:

1. **Ordem determinística.** Chunks são ordenados por `path` e `headingPath`,
   nunca por score — dois pedidos equivalentes precisam produzir bytes idênticos.
2. **Continuidade na tarefa.** Dentro de uma mesma tarefa, o pacote de contexto
   não é recalculado a cada volta do laço de ferramentas. Recalcular invalidaria
   o prefixo e custaria mais do que o contexto economiza.

## 8. Observabilidade

| Métrica | Uso |
|---|---|
| `zordon.context.tokens` | Por agente e intenção |
| `zordon.context.chunks` | Quantidade entregue |
| `zordon.context.truncated` | Orçamento estourou — sinal de tarefa grande demais |
| `zordon.context.hit_rate` | Chunks entregues que o agente efetivamente citou |
| `zordon.context.latency` | Alvo: abaixo de 60 ms |

`zordon.context.hit_rate` é a métrica que diz se o router está funcionando. Baixa
significa que ele está entregando ruído caro; alta e com `truncated` frequente
significa que as tarefas precisam ser menores.

## 9. Segurança

- O filtro de `securityScope` é o **primeiro** estágio, não o último.
- Chunk entregue é envelopado como **documentação**, não como instrução
  ([Segurança §6](../security/model.md#6-prompt-injection)).
- O pacote de contexto não concede nada: permissão vem da política.
- `ContextPackage.sources` é obrigatório — toda afirmação do agente baseada em
  documentação deve poder citar de onde veio.

## 10. Casos de erro

| Falha | Comportamento |
|---|---|
| Orçamento insuficiente para os obrigatórios | Falha a tarefa com `ERR_BUDGET_EXCEEDED`; não entrega contexto parcial de segurança |
| Nenhum chunk relevante | Entrega apenas os obrigatórios e marca no evento |
| Índice indisponível | Entrega os obrigatórios por caminho fixo; agente opera degradado |
| SPEC referenciada não existe | Falha antes de executar — agente não implementa sem SPEC |

A primeira linha é deliberada: se não cabe a tabela de classificação de risco no
orçamento, a tarefa não roda. Cortar contexto de segurança para caber no
orçamento é exatamente o tipo de otimização que produz incidente.

## 11. Riscos

| Risco | Mitigação |
|---|---|
| Router omite documento essencial | Lista de obrigatórios por intenção (§5) |
| Contexto recuperado desatualizado | `RAG_STALE` bloqueia a [DoD](../process/definition-of-done.md) |
| Recalcular contexto invalida cache de prompt | Continuidade na tarefa (§7) |
| Agente ignora o contexto entregue | `hit_rate` medido; `CodeReviewAgent` verifica aderência à SPEC |

## 12. Testes

- Consultas golden: cada uma com o conjunto esperado de documentos.
- Nenhum agente recebe chunk acima do `securityScope`, por agente.
- Obrigatórios de §5 presentes em todo pacote da intenção correspondente.
- Duas requisições idênticas produzem bytes idênticos (cache).

## 13. Critérios de aceite

- `CA-1` Um pacote típico fica abaixo de 8.000 tokens.
- `CA-2` Os obrigatórios de §5 estão sempre presentes.
- `CA-3` Nenhum chunk acima do `securityScope` do agente é entregue.
- `CA-4` Duas requisições idênticas produzem o mesmo pacote, byte a byte.
- `CA-5` Orçamento insuficiente para os obrigatórios falha a tarefa, não a
  degrada.
