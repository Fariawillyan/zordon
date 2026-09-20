---
document: architecture-extensions
module: architecture
section: extensions
version: 1
updatedAt: 2026-09-18
securityLevel: internal
tags: [extensoes,plugins,adaptadores,versionamento,manifesto,skills,agentes,mcp]
specId: null
---

# Extensões e versionamento

Como o Zordon ganha capacidades sem tocar no núcleo, e como cada uma é
versionada, aprovada e revertida. Decisão em
[ADR-0034](../adr/ADR-0034-manifesto-de-extensao-versionado.md).

## 1. Tipos de extensão

| Tipo | Onde roda | Como entra hoje | Documento |
|---|---|---|---|
| Skill | Processo do núcleo (in-process) | Código Java revisado | [ADR-0012](../adr/ADR-0012-skills-in-process-primeiro.md) |
| Servidor MCP | Processo próprio | Configuração | [MCP](../specs/mcp/design.md) |
| Agente | Runtime comum | Arquivo TOML | [Catálogo](../agents/catalog.md) |
| Provider de IA | Adaptador no `zordon-ai` | `config.toml` | [ADR-0026](../adr/ADR-0026-provider-agnostico.md) |
| Automação e workflow | Scheduler | Criada pelo usuário ou proposta pelo Zordon | [Automação](../specs/automation/design.md) |

Não existe carregamento dinâmico de JAR de terceiros no núcleo: código externo
entra como servidor MCP, isolado em processo próprio. Skill nova é código do
projeto, com SPEC e revisão.

## 2. Manifesto

Toda extensão tem `zordon-extension.toml`:

```toml
id = "skill:dev.httpCheck"
kind = "skill"                       # skill | mcp | agent | provider | automation | workflow
version = "1.2.0"                    # semver
requiresCore = ">=0.3 <0.5"
capabilities = ["WEB"]               # Capability Registry (ADR-0033)
maxRisk = "GREEN"
source = "zordon"                    # ou URL e revisão para origem externa
sha256 = "…"                         # do conteúdo empacotado
spec = "SPEC-031"                    # quando nasceu de uma SPEC

[approval]
approvedAt = "2026-10-02T14:03:00Z"
approvedHash = "…"                   # o que o usuário aprovou
```

## 3. Registro

O `ExtensionRegistry` (núcleo) carrega uma extensão só se:

1. `requiresCore` inclui a versão do núcleo;
2. o `sha256` atual bate com o `approvedHash`;
3. as capacidades declaradas cabem no teto do tipo.

Mudou sem aprovação → **suspensa**, com aviso e evento no trace. É o que pega um
servidor MCP que trocou de superfície ([MCP §3](../specs/mcp/design.md#envenenamento-de-mcp))
ou uma skill editada fora do fluxo.

## 4. Versões e reversão

- Cada versão aprovada fica em `~/.zordon/extensions/<id>/<version>/`. Nada é
  sobrescrito nem apagado ([ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md)).
- `current` aponta para a versão ativa; reverter é apontar para a anterior —
  uma ação YELLOW, na tela.
- Automações e workflows **fixam** a versão das skills e agentes que chamam
  (`uses = "skill:dev.httpCheck@1.2"`); uma versão nova não muda o comportamento
  de uma automação aprovada sem nova aprovação.

## 5. Quando o Zordon altera o próprio ecossistema

Complementa [Auto-modificação](../process/self-modification.md):

- O Zordon nunca edita a versão aprovada no lugar. Ele gera uma **versão nova**,
  com SPEC quando a mudança é de comportamento, testes e o diff, e pede aprovação.
- Extensões do núcleo de confiança ([ADR-0024](../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md))
  não são extensões: não têm manifesto nem podem ser trocadas por este caminho.
- SPECs, ADRs e agentes seguem o mesmo princípio: status e versão no cabeçalho,
  mudança por versão nova, histórico no git.

## 6. Adaptadores

Um adaptador é uma extensão que traduz um sistema externo para um contrato do
Zordon (`AiProvider`, `WindowsBridge`, `KnowledgeGraph`). Ele declara o contrato
que implementa e passa no kit de contrato daquele tipo — como os providers
passam no kit do [SPEC-004](../specs/core/SPEC-004-providers-configuraveis.md).

## 7. Marco

Formato do manifesto no M3, quando o `ToolRegistry` e as skills entram; MCP no
M4; agentes no M5; reversão pela tela e versionamento de automações no M6;
geração de versões pelo próprio Zordon no M8.
