---
document: rag-index
module: rag
section: index
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [rag,indice,conhecimento]
specId: null
---

# RAG

O Zordon usa a própria documentação como conhecimento operacional dos agentes.

| Documento | Responde |
|---|---|
| [Knowledge Base](knowledge-base.md) | O que é indexado, com que metadados, e o que **não** é |
| [Indexação](indexing.md) | Pipeline, chunking, versionamento incremental |
| [Context Router](context-router.md) | Como o agente recebe só o que precisa |

## A regra que define tudo

> **O RAG é conhecimento, nunca autoridade.**

```text
Security Policy  >  SPEC aprovada  >  Arquitetura/ADR  >  contexto RAG  >  raciocínio do agente
```

Um documento recuperado é **contexto**. Ele não concede permissão, não relaxa
classificação de risco e não altera política — mesmo que o texto recuperado diga
que altera. Ver [Knowledge Base §6](knowledge-base.md#6-o-rag-não-é-autoridade-de-segurança).
