---
document: adr-0037
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,conhecimento,grafo,memoria,relacoes]
specId: null
---

# ADR-0037 — Relações entre coisas ficam na memória, sem banco de grafo

**Status:** Aceito · 2026-09-18

## Contexto

O RAG responde "o que diz o documento" ([ADR-0020](ADR-0020-rag-como-conhecimento.md));
a memória responde "o que sabemos do usuário e do contexto"
([ADR-0008](ADR-0008-sqlite-como-memoria.md)). Nenhum dos dois responde bem a
perguntas de relação: *qual serviço usa este banco? em que servidor ele roda?
quem é o responsável? o que quebra se eu parar este container?* São perguntas de
grafo: projeto → serviço → banco → servidor → responsável → dependência.

## Alternativas

| Alternativa | Por que não |
|---|---|
| Banco de grafo dedicado (Neo4j etc.) | Um serviço a mais, pesado, para um volume que cabe em milhares de arestas |
| Pedir ao modelo que deduza as relações a cada pergunta | Caro, instável, sem fonte |
| **Tabelas de entidades e relações no SQLite da memória, com proveniência** | — |

## Decisão

O `MemoryStore` ganha `entity(id, kind, name)` e `relation(from, kind, to,
source, confidence, observedAt)`. Relações vêm de fontes verificáveis —
`docker compose`, arquivos de configuração, o RAG, o usuário — e cada uma guarda
de onde veio. Consultas de vizinhança e de impacto (até N saltos) são SQL
recursivo (`WITH RECURSIVE`). Nada de relação sem fonte.

Um banco de grafo dedicado só entra se as consultas ficarem lentas com dados
reais; a interface `KnowledgeGraph` isola essa troca.

Detalhes em [Memória §10](../specs/memory/design.md#10-relações-grafo-de-conhecimento).

## Consequências

- "O que quebra se eu parar o Postgres?" vira consulta, não palpite.
- Relação obsoleta é detectável: a fonte mudou, a relação é revalidada.
- Entra no M8, com a plataforma de desenvolvimento; antes disso, a tabela existe
  vazia.
