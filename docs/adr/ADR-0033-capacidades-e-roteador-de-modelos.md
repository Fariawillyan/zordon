---
document: adr-0033
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,capacidades,roteamento,modelos,custo,privacidade]
specId: null
---

# ADR-0033 — Capacidades, não nomes; o roteador escolhe quem executa

**Status:** Aceito · 2026-09-18

## Contexto

O núcleo já não pertence a um provider: o modelo é escolhido por **papel**
(`conversation`, `routing`, `agent_heavy`…) em `config.toml`
([ADR-0026](ADR-0026-provider-agnostico.md), [SPEC-004](../specs/core/SPEC-004-providers-configuraveis.md)).
Papel diz *para que* o modelo serve, não *o que ele sabe fazer*. Uma tarefa com
imagem precisa de visão; uma consulta sobre arquivos pessoais não pode sair da
máquina; uma pergunta simples não deve pagar o modelo mais caro. O mesmo vale
para agentes e ferramentas: "Codex" e "Claude" são nomes; o que o Orchestrator
precisa é de quem sabe `CODE`, `TERMINAL`, `RESEARCH`.

## Alternativas

| Alternativa | Por que não |
|---|---|
| Continuar só com papéis fixos | Não expressa visão, privacidade, custo; cada caso novo vira papel novo |
| Roteamento pelo próprio LLM | Caro, lento e não determinístico onde a regra resolve |
| **Registro de capacidades + roteador determinístico por restrições** | — |

## Decisão

1. **Capability Registry**: um vocabulário fechado e versionado de capacidades —
   `TEXT`, `CODE`, `VISION`, `WEB`, `TERMINAL`, `RESEARCH`, `SPEECH`,
   `LONG_CONTEXT`, `TOOL_USE`, `LOCAL_ONLY` — que modelos, agentes, skills e MCPs
   **declaram**. Declaração é conferida por teste de contrato quando possível
   (ex.: `VISION` exige aceitar imagem).
2. **Model Router**: dada a tarefa, filtra por **restrições duras** (capacidade
   exigida, privacidade — dado sensível só em `LOCAL_ONLY` —, orçamento, janela
   de contexto) e ordena os que sobram por **preferência** (custo, latência,
   qualidade medida pelo Evaluation Engine). Os papéis continuam como atalho de
   configuração: um papel é uma consulta salva ao roteador.
3. A decisão do roteador vai para o evento do turno e para o trace: qual modelo,
   por quê, qual alternativa foi descartada e por qual restrição.

Detalhes em [Capacidades e roteamento](../specs/core/capabilities-and-routing.md).

## Consequências

- Nenhum provider novo exige código no roteador: declara capacidades e custos.
- Privacidade vira restrição verificável, não conselho: conteúdo marcado
  sensível (contaminação, [Segurança §6](../security/model.md)) nunca é roteado a
  modelo sem `LOCAL_ONLY`.
- A decisão de provider do M3 (assinatura via CLI) entra como mais um provider
  com capacidades e custo declarados.
