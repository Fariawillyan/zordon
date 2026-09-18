---
document: process-index
module: process
section: index
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [processo,spec,qualidade,indice]
specId: null
---

# Processo

Como se trabalha no Zordon. Estes documentos governam **como** funcionalidades
nascem, são implementadas e são consideradas prontas.

| Documento | Responde |
|---|---|
| [Spec-Driven Development](spec-driven-development.md) | Como uma ideia vira funcionalidade |
| [Template de SPEC](spec-template.md) | O formato obrigatório de uma SPEC |
| [Definition of Done](definition-of-done.md) | Quando algo está realmente pronto |
| [Padrões de código](code-standards.md) | Clean Code, smells, complexidade, naming, comentários |
| [Auto-modificação](self-modification.md) | Como o Zordon altera o próprio projeto e os de terceiros |
| [Rastreabilidade](traceability.md) | Como ligar SPEC ↔ commit ↔ código ↔ teste ↔ doc |

## As regras que resumem tudo

> **Documentação não é subproduto do código. Ela faz parte da implementação.**

```text
IDEIA → SPEC → REVIEW → PLANO → IMPLEMENTAÇÃO → TESTES → VALIDAÇÃO → DOCUMENTAÇÃO
```

Alterando um projeto — o próprio ou de terceiro:

```text
IDEIA → RAG → SPEC → ADR (se necessário) → PLANO → AGENTS → IMPLEMENTAÇÃO
```

Nunca `IDEIA → CÓDIGO`. Nunca alterar um projeto silenciosamente.

E compilar não é concluir: uma tarefa só termina com
`SPEC + implementação + testes + segurança + documentação + RAG + auditoria`.
Ver [Definition of Done](definition-of-done.md).
