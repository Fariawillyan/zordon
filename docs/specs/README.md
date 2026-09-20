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
| [001](process/SPEC-001-validacao-de-documentacao.md) | Validação de documentação e rastreabilidade no build | process | DONE | M0 |
| [002](core/SPEC-002-fundacao-zwp-e-nucleo.md) | Fundação: build, contrato ZWP e núcleo que sobe | core | DONE | M0 |
| [003](core/SPEC-003-chat-com-streaming.md) | Conversa de texto com streaming | core | DONE | M1 |
| [004](core/SPEC-004-providers-configuraveis.md) | Providers configuráveis e adaptador compatível com OpenAI | core | DONE | M1 |
| [005](ui/SPEC-005-shell-do-desktop.md) | Shell do desktop | ui | DONE | M2 |
| [006](voice/SPEC-006-tela-e-estado-da-voz.md) | Tela de Voz e estado da voz | voice | DONE | M2 |
| [007](host/SPEC-007-host-do-windows.md) | Host do Windows | host | DONE | M2 |
| [008](voice/SPEC-008-console-visual-e-efeitos-sonoros.md) | Console visual e efeitos sonoros | voice | DONE | M2 |
| [009](voice/SPEC-009-frames-de-audio-e-teste-do-microfone.md) | Frames de áudio e teste do microfone | voice | DONE | M2 |
| [010](ui/SPEC-010-shell-compacto-centrado-na-voz.md) | Shell compacto centrado na voz | ui | DONE | M2 |
| [011](voice/SPEC-011-motor-de-voz-ouvir-e-falar.md) | Motor de voz: ouvir e falar | voice | DONE | M2 |
| [012](voice/SPEC-012-voice-first-narracao-e-estados.md) | Voice-first: narração, estados visuais e trace | voice | DONE | M2 |
| [013](voice/SPEC-013-palavra-de-ativacao-e-conversa-sem-clique.md) | Palavra de ativação e conversa sem clique | voice | IMPLEMENTING | M2 |
| [014](security/SPEC-014-auditoria-validador-e-motor-de-permissao.md) | Auditoria, validador de comandos e motor de permissão | security | IMPLEMENTING | M3 |
| [015](security/SPEC-015-pedido-de-permissao-notificacoes-e-kill-switch.md) | Pedido de permissão, notificações e kill switch | security | IMPLEMENTING | M3 |
| [016](security/SPEC-016-execucao-mediada-ferramentas-e-ponte-windows.md) | Execução mediada, ferramentas e ponte com o Windows | security | IMPLEMENTING | M3 |
| [017](security/SPEC-017-cofre-de-quarentena.md) | Cofre de quarentena | security | IMPLEMENTING | M3 |
| [018](core/SPEC-018-provider-por-assinatura-claude-cli.md) | Provider por assinatura: `claude-cli` | core | IMPLEMENTING | M3 |
| [019](core/SPEC-019-ferramentas-pelo-modelo.md) | Ferramentas pelo modelo: o laço do turno | core | IMPLEMENTING | M4 |
| [020](mcp/SPEC-020-cliente-mcp.md) | Cliente MCP | mcp | IMPLEMENTING | M4 |
| [021](memory/SPEC-021-memoria-de-longo-prazo.md) | Memória de longo prazo | memory | IMPLEMENTING | M5 |
| [022](agents/SPEC-022-agentes-como-configuracao.md) | Agentes como configuração | agents | IMPLEMENTING | M5 |
| [023](agents/SPEC-023-planos-duraveis-e-verificacao.md) | Planos duráveis e conclusão verificada | agents | IMPLEMENTING | M5 |
| [024](automation/SPEC-024-monitor-do-sistema.md) | Monitor do sistema | automation | IMPLEMENTING | M6 |
| [025](automation/SPEC-025-automacoes.md) | Automações | automation | IMPLEMENTING | M6 |
| [026](defense/SPEC-026-deteccao-e-correlacao.md) | Detecção e correlação | defense | IMPLEMENTING | M7 |
| [027](defense/SPEC-027-resposta-e-disjuntor.md) | Resposta, disjuntor e comportamento do host | defense | IMPLEMENTING | M7 |
| [028](rag/SPEC-028-base-de-conhecimento.md) | Base de conhecimento e RAG | rag | IMPLEMENTING | M8 |
| [029](process/SPEC-029-engenharia-preflight-e-uso.md) | Perfil de engenharia, preflight e uso de tokens | process | IMPLEMENTING | M8 |

A numeração é **global e sequencial**, não por módulo. Número nunca é
reutilizado, nem quando a SPEC é rejeitada.

## Estados

`DRAFT` → `REVIEW` → `APPROVED` → `IMPLEMENTING` → `DONE`
(com `BLOCKED` e `REJECTED` como saídas laterais)

Um agente **recusa implementar** SPEC que não esteja em `APPROVED` ou posterior.
