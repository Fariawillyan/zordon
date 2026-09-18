---
document: adr-0021
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,agentes,perfis,least-privilege]
specId: null
---

# ADR-0021 — Dois perfis de agente sobre um único mecanismo

**Status:** Aceito · 2026-09-17

## Contexto

O Zordon tem agentes que servem a dois propósitos diferentes:

- **Assistente** — `system`, `developer`, `research`, `automation` e agentes de
  projeto (`projeto-<nome>`). Acompanham o produto e ajudam o usuário com a
  máquina dele.
- **Engenharia** — `architecture`, `spec`, `documentation`, `java`, `javafx`,
  `testing`, `codereview`, `devops`… Constroem o próprio Zordon.

Eles se parecem (ambos são "agentes de IA com ferramentas") mas servem a coisas
distintas, e há sobreposição confusa: `developer` (assistente) e `java`
(engenharia) fazem coisas parecidas para donos diferentes.

## Alternativas

**A. Tratar como sistemas separados.** Um framework de agentes para o produto,
outro para o desenvolvimento. Cada um evolui livre. Mas: duplica orquestrador,
registro, orçamento, permissão e observabilidade — e as duas cópias divergem. Pior,
o time de engenharia deixaria de exercitar o produto.

**B. Um único conjunto de agentes, sem distinção.** Simples, e perigoso: um
agente de engenharia com escrita no repositório não deveria ter o mesmo escopo
de um agente que responde ao usuário sobre o Docker dele. Sem distinção de
perfil, o `allowedPaths` de cada um precisaria ser negociado caso a caso.

**C. Um mecanismo, dois perfis declarados.**

## Decisão

**Alternativa C.** Um `AgentRegistry`, um orquestrador, um modelo de orçamento,
uma cadeia de permissão. O que muda é a **definição** de cada agente:

```java
public enum AgentProfile { ASSISTANT, ENGINEERING }
```

| Perfil | Serve a | `allowedPaths` típico | Onde a definição mora |
|---|---|---|---|
| `ASSISTANT` | O usuário | Workspaces do usuário | `~/.zordon/agents/` |
| `ENGINEERING` | O repositório do Zordon | Apenas o repositório | `docs/agents/definitions/` |

A consequência elegante: **o time de engenharia é o Zordon sendo usado para
construir o Zordon.** Não é um sistema paralelo — é dogfooding estrutural. Todo
problema do orquestrador, do orçamento ou da seleção de ferramentas aparece
primeiro para quem está construindo, o que é exatamente onde se quer que apareça.

### Least privilege por agente

Nenhum agente tem acesso a tudo. Cada definição declara `capabilities`,
`allowedTools`, `allowedMcp`, `allowedPaths`, `requiredSpecs`, `securityScope` e
`tokenBudget` — nenhum campo é opcional, e um agente sem `securityScope` não é
carregado.

Duas restrições que merecem registro por serem contraintuitivas:

**`TestingAgent` não escreve fora de `src/test/`.** Um agente de teste com
permissão de editar produção converge para "ajustar a asserção até passar" — a
pior falha possível quando ninguém está olhando. Teste falhando é *reportado* ao
agente de domínio.

**`CodeReviewAgent` tem teto GREEN e não escreve em lugar nenhum.** Um revisor
que pode alterar o que revisa não é um revisor. Ele produz o achado com proposta
de refatoração; a correção volta ao agente de domínio.

## Consequências

**Positivas.** Uma implementação para manter. O produto é exercitado pela própria
construção. Least privilege explícito e uniforme. Adicionar agente é criar um
arquivo, nos dois perfis. Métricas de token e disjuntor funcionam igual para os
dois.

**Negativas.** A distinção precisa ser explicada — alguém lendo o catálogo pela
primeira vez vê 19 agentes e se assusta. Sobreposição real entre `developer` e
`java` exige critério de roteamento claro. Agentes de engenharia rodando na
máquina do usuário precisam de `allowedPaths` bem restrito, senão o risco
vaza de um contexto para o outro.

**Mitigação da última:** o perfil é declarado e verificado. Um agente
`ENGINEERING` com `allowedPaths` fora do repositório não é carregado.

## Nota de escopo

O pedido original citava Spring sob o `JavaAgent`. O Zordon deliberadamente
**não usa** Spring, Quarkus ou Micronaut — o núcleo é um processo long-running
com injeção manual no composition root
([Componentes §6](../architecture/components.md#6-version-catalog)). O `JavaAgent`
opera sobre Java 25 puro e Gradle. Registrado aqui para que a divergência entre o
pedido e a implementação seja deliberada e rastreável, não um esquecimento.
