---
document: adr-0034
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,extensoes,plugins,versionamento,skills,agentes]
specId: null
---

# ADR-0034 — Toda extensão tem manifesto versionado

**Status:** Aceito · 2026-09-18

## Contexto

O Zordon cresce sem tocar no núcleo: skills ([ADR-0012](ADR-0012-skills-in-process-primeiro.md)),
servidores MCP ([MCP §7](../specs/mcp/design.md#7-adicionando-capacidade-sem-tocar-no-núcleo)),
agentes em TOML ([Catálogo](../agents/catalog.md)), providers de IA
([ADR-0026](ADR-0026-provider-agnostico.md)), automações. Cada um tem um formato
próprio, e nenhum diz **qual versão** está instalada, com que versão do núcleo é
compatível, se mudou desde que foi aprovado ou como voltar atrás. Quando o Zordon
passar a modificar o próprio ecossistema ([Auto-modificação](../process/self-modification.md)),
isso vira pré-requisito: não se reverte o que não tem versão.

## Alternativas

| Alternativa | Por que não |
|---|---|
| Cada tipo com seu formato, sem versão | Sem reversão, sem compatibilidade, sem saber o que mudou |
| Sistema de plugins em JAR com carregamento dinâmico | Código de terceiros no processo do núcleo; contraria o isolamento de MCP e o núcleo de confiança |
| **Manifesto comum (`zordon-extension.toml`) com versão, compatibilidade, capacidades e hash** | — |

## Decisão

Toda extensão — skill, agente, MCP, provider, automação, workflow — tem um
manifesto com:

- `id`, `kind`, `version` (semver) e `requiresCore` (faixa de versão do núcleo);
- `capabilities` do [Capability Registry](ADR-0033-capacidades-e-roteador-de-modelos.md) e o teto de risco;
- `sha256` do conteúdo empacotado e, para origem externa, a fonte;
- `approvedAt` e `approvedHash`: o que o usuário aprovou.

O `ExtensionRegistry` carrega só manifestos cujo hash atual bate com o aprovado;
mudou sem aprovação → a extensão fica suspensa, com aviso. Cada versão aprovada
fica guardada em `~/.zordon/extensions/<id>/<version>/`; voltar é apontar para a
anterior. Automações e workflows fixam a versão das skills que chamam.

Detalhes em [Extensões e versionamento](../architecture/extensions.md).

## Consequências

- Mudança silenciosa de um MCP ou skill (o caso de [MCP §3](../specs/mcp/design.md#envenenamento-de-mcp))
  vira suspensão automática, não surpresa.
- O Zordon, ao alterar uma skill ou agente, produz uma versão nova que precisa de
  aprovação — nunca edita a aprovada no lugar.
- SPECs já têm versão e status; o manifesto liga a extensão à SPEC que a criou.
