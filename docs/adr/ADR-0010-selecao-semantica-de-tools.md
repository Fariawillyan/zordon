---
document: adr-0010
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,selecao,semantica,de,tools]
specId: null
---

# ADR-0010 — Seleção semântica de ferramentas em dois estágios

**Status:** Aceito · 2026-09-17

## Contexto

O briefing foi explícito: não enviar todas as ferramentas disponíveis à IA em
cada prompt, e criar um mecanismo de seleção semântica para economizar contexto.

A aritmética justifica o requisito. Com 7 MCP servers e as Skills nativas,
chega-se facilmente a 80 ferramentas. A ~300 tokens por descritor (nome,
descrição, JSON Schema), são **~24.000 tokens de entrada em cada volta do laço
de ferramentas**. Um turno com 3 voltas gasta 72.000 tokens só descrevendo
capacidades — a maior parte das quais irrelevante para o pedido.

O custo não é o único problema. Um modelo com 80 opções escolhe pior do que um
com 12 bem selecionadas.

## Alternativas

**A. Mandar tudo.** Simples e com recall perfeito. Caro e com precisão pior.

**B. Escopo estático por agente.** Cada agente declara suas ferramentas; manda-se
só elas. Reduz bastante e é determinístico. Mas um `DeveloperAgent` legítimo
ainda precisa de Git + Maven + Gradle + npm + Docker + arquivos — 40 ferramentas.
Não resolve sozinho.

**C. Só busca semântica.** Embeddings sobre as descrições, top-K por
similaridade. Reduz muito, mas erra em identificador exato: buscar
"listContainers" por similaridade vetorial é pior do que por correspondência
lexical, e nomes de ferramenta são justamente identificadores.

**D. Dois estágios: filtro duro por escopo, depois relevância híbrida
(lexical + vetorial).**

## Decisão

**Alternativa D**, com dois complementos.

```text
  80 ferramentas
       │
       ▼  ESTÁGIO 1 — filtro duro (determinístico)
  escopo do agente (include/exclude) · teto de permissão · servidores conectados
       │
       ▼  30 ferramentas
       │  ESTÁGIO 2 — relevância (BM25 + cosseno, fundidos por RRF)
       ▼
  12 ferramentas  ∪  pinned do agente  →  prompt
```

**Complemento 1 — continuidade no turno.** Ferramentas já usadas no turno
permanecem selecionadas nas voltas seguintes. Trocar o conjunto entre voltas
invalida o cache de prompt (que casa por prefixo, e as ferramentas vêm primeiro
na ordem de renderização) e confunde o modelo.

**Complemento 2 — meta-ferramenta `tools.search`.** Sempre presente. Se o modelo
achar que falta algo, ele busca por descrição e recebe até 5 descritores. Isso
converte um problema de recall (irrecuperável: o modelo não sabe o que não viu)
num problema de mais uma volta (recuperável e barato). É a rede de segurança que
torna aceitável um top-K agressivo.

**Nota de implementação.** Quando o provider oferecer busca de ferramentas do
lado do servidor (ferramentas marcadas para carregamento diferido, com uma
ferramenta de busca embutida), o adaptador pode delegar o estágio 2 a ele sem
mudar o contrato do `ToolRegistry`. Atenção à regra da API: pelo menos uma
ferramenta precisa permanecer carregada — marcar todas como diferidas é erro.

## Consequências

**Positivas.** De ~24.000 para ~4.000 tokens de entrada por volta. Escolha do
modelo melhora com menos opções relevantes. O estágio 1 é uma barreira de
segurança real: uma ferramenta acima do teto do agente **nunca é sequer
oferecida**, o que é mais forte do que ser negada depois. Embeddings calculados
uma vez, no registro da ferramenta.

**Negativas.** Um erro de seleção deixa o modelo sem a ferramenta certa, e o
sintoma ("o Zordon não fez o que pedi") é confuso de diagnosticar. Depende de um
provider de embeddings disponível. Adiciona ~20 ms por turno.

**Mitigações.**

- A métrica `zordon.tool.offered`, comparada com `zordon.tool.calls`, é
  obrigatória ([Observabilidade §3](../operations/observability.md#ferramentas-agentes-mcp)).
  Ela é o que torna o erro de seleção diagnosticável: uma ferramenta nunca
  oferecida aponta para a seleção; oferecida e nunca chamada aponta para a
  descrição dela.
- O trace registra **quais ferramentas foram oferecidas**, não só quais foram
  chamadas.
- `pinned` por agente garante as ferramentas de uso constante.
- Sem embeddings, degrada para BM25 puro — pior, mas funcional.

**Consequência para autores de ferramentas.** A descrição de uma ferramenta deixa
de ser documentação e passa a ser **a chave de recuperação dela**. Uma descrição
vaga faz a ferramenta desaparecer. Isso precisa estar na revisão de código de toda
Skill nova.
