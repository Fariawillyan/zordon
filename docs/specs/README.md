---
document: specs-index
module: specs
section: index
version: 2
updatedAt: 2026-09-17
securityLevel: public
tags: [specs,indice,rastreabilidade]
specId: null
---

# SPECs

Toda funcionalidade relevante nasce aqui antes de virar código.
Processo em [Spec-Driven Development](../process/spec-driven-development.md);
formato em [Template](../process/spec-template.md).

## Estrutura

```text
specs/
├── <módulo>/design.md          visão do módulo — estável, muda pouco
└── <módulo>/SPEC-XXX-*.md      especificações — uma por funcionalidade
```

`design.md` responde "como este módulo funciona". `SPEC-XXX` responde "o que
vamos construir agora, e como saberemos que ficou pronto".

## Módulos

| Módulo | Design | Agente responsável |
|---|---|---|
| [core](core/design.md) | IA, providers, Intent Router, contexto, custo | `JavaAgent` |
| [voice](voice/design.md) | Wake word, VAD, STT, TTS, latência | `VoiceAgent` |
| [agents](agents/design.md) | Runtime, orçamento, delegação, disjuntor | `JavaAgent` |
| [mcp](mcp/design.md) | Cliente MCP, ToolRegistry, seleção semântica | `McpAgent` |
| [memory](memory/design.md) | Três níveis, SQLite, recuperação híbrida | `DatabaseAgent` |
| [automation](automation/design.md) | Scheduler, watchers, workflows | `AutomationAgent` |
| [ui](ui/design.md) | JavaFX, tray, overlay, design system | `JavaFxAgent` |
| [rag](../rag/knowledge-base.md) | Knowledge base, indexação, context router | `RagAgent` |
| [security](security/README.md) | → norma em `docs/security/` | `SecurityAgent` |
| [defense](defense/README.md) | → norma em `docs/security/defense.md` | `DefenseAgent` |

O módulo de UI também possui um [design system](ui/design-system.md),
[layout desktop](ui/desktop-layout.md) e [prévia navegável](ui/preview/index.html),
em DRAFT. São documentos de design; não consomem IDs de SPEC nem autorizam
implementação de produção.

## Índice de SPECs

| ID | Nome | Módulo | Status | Marco |
|---|---|---|---|---|
| — | _nenhuma SPEC escrita ainda_ | — | — | — |

A primeira SPEC nasce no M0, e a numeração é **global e sequencial**, não por
módulo. Número nunca é reutilizado, nem quando a SPEC é rejeitada.

## Estados

`DRAFT` → `REVIEW` → `APPROVED` → `IMPLEMENTING` → `DONE`
(com `BLOCKED` e `REJECTED` como saídas laterais)

Um agente **recusa implementar** SPEC que não esteja em `APPROVED` ou posterior.
