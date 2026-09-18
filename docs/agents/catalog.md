---
document: agents-catalog
module: agents
section: catalog
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [agentes,catalogo,registry,least-privilege,capabilities]
specId: null
---

# Catálogo de agentes

## 1. Objetivo

Declarar cada agente do Zordon: escopo, capacidades, ferramentas permitidas,
teto de permissão e orçamento.

## 2. `AgentRegistry`

Todo agente declara o mesmo conjunto de campos. Nenhum campo é opcional — um
agente sem `securityScope` declarado não é carregado.

```java
public record AgentDefinition(
        AgentId id,
        String name,
        String description,
        AgentProfile profile,          // ASSISTANT | ENGINEERING
        Set<Capability> capabilities,  // o que sabe fazer
        ToolScope allowedTools,        // include/exclude, com prefixo
        Set<String> allowedMcp,        // servidores MCP permitidos
        Set<PathPattern> allowedPaths, // onde pode ler/escrever
        Set<String> requiredSpecs,     // SPECs que precisa ler antes de agir
        SecurityScope securityScope,   // teto de permissão + nível de doc no RAG
        Budget tokenBudget,
        ModelPolicy modelPolicy) {}

public record SecurityScope(
        RiskLevel permissionCeiling,   // GREEN | YELLOW | RED
        SecurityLevel maxDocLevel,     // public | internal | restricted
        Set<Effect> allowedEffects) {}
```

`allowedEffects` é a trava mais forte: um agente sem `WRITE_FS` no conjunto não
consegue escrever arquivo **nem pedindo**, porque as ferramentas que produzem
esse efeito não entram na seleção que chega ao modelo
([ADR-0010](../adr/ADR-0010-selecao-semantica-de-tools.md)).

## 3. Least privilege na prática

| Princípio | Aplicação |
|---|---|
| Nenhum agente vê todas as ferramentas | `allowedTools` filtra antes da relevância |
| Nenhum agente lê toda a documentação | `maxDocLevel` filtra no [Context Router](../rag/context-router.md) |
| Nenhum agente escreve fora do escopo | `allowedPaths`, verificado pelo `CommandValidator` |
| Nenhum agente eleva o próprio teto | Delegação usa o **mínimo** dos dois tetos |
| Nenhum agente contorna o motor de permissão | Não há caminho; verificado por ArchUnit |

## 4. Perfil assistente

Agentes que acompanham o produto e servem o usuário.
Detalhe de comportamento em [Runtime](../specs/agents/design.md#2-os-agentes-iniciais).

| Agente | Teto | Efeitos | Domínio |
|---|---|---|---|
| `zordon` | YELLOW | READ_FS, WRITE_FS, SPAWN_PROCESS | Geral; fallback quando o roteador não decide |
| `system` | YELLOW | READ_FS, SPAWN_PROCESS | CPU, RAM, GPU, processos, disco, rede, WSL, Docker |
| `developer` | YELLOW | READ_FS, WRITE_FS, SPAWN_PROCESS | Git, Java, Maven, Gradle, React, C++, Unreal, builds, logs |
| `research` | **GREEN** | READ_FS, NETWORK | Pesquisa e coleta |
| `automation` | YELLOW | READ_FS, WRITE_FS | Rotinas e workflows |
| `projeto-<nome>` | YELLOW | READ_FS, WRITE_FS, SPAWN_PROCESS | Um projeto específico do usuário |

`research` com teto **GREEN** é a decisão de segurança mais importante da tabela:
ele consome a web, a fonte mais provável de prompt injection, e não pode escrever
nem executar mesmo que seja completamente convencido.

## 5. Perfil engenharia

Agentes que constroem o Zordon. Todos têm `maxDocLevel` alto (precisam ler a
documentação) e teto de escrita **restrito ao repositório**.

### ArchitectureAgent

| Campo | Valor |
|---|---|
| Responsável por | Arquitetura, boundaries, módulos, dependências, ADRs |
| Teto | YELLOW |
| Efeitos | READ_FS, WRITE_FS (apenas `docs/`) |
| Ferramentas | Leitura de código, busca, escrita em `docs/architecture/` e `docs/adr/` |
| **Não faz** | Não implementa funcionalidade completa. Ele decide onde as coisas moram, não as constrói |

### SpecAgent

| Campo | Valor |
|---|---|
| Responsável por | Criar e revisar SPECs, detectar requisito faltante, definir critérios de aceite |
| Teto | YELLOW |
| Efeitos | READ_FS, WRITE_FS (apenas `docs/specs/`) |
| SPECs obrigatórias | [Template](../process/spec-template.md), [SDD](../process/spec-driven-development.md) |
| **Não faz** | Não implementa. Não aprova a própria SPEC |

### DocumentationAgent

| Campo | Valor |
|---|---|
| Responsável por | Documentação, consistência, links, índices, reindexação do RAG |
| Teto | YELLOW |
| Efeitos | READ_FS, WRITE_FS (apenas `docs/`) |
| Aciona | [Indexação incremental](../rag/indexing.md#4-indexação-incremental) |
| **Não faz** | Não altera código. Não inventa comportamento não implementado |

Ciclo automático após mudança relevante:

```text
   Código mudou → detecta documentação afetada (TraceabilityIndex)
                → atualiza os documentos → reindexa os chunks alterados
```

### JavaAgent

| Campo | Valor |
|---|---|
| Responsável por | Java 25, concorrência (threads virtuais), performance, arquitetura interna dos módulos |
| Teto | YELLOW |
| Efeitos | READ_FS, WRITE_FS, SPAWN_PROCESS (build e teste) |
| **Não faz** | Não toca `zordon-security` nem `zordon-defense` sem o `SecurityAgent` |

> **Nota de escopo:** o pedido original citava Spring sob este agente. O Zordon
> deliberadamente **não usa** Spring, Quarkus ou Micronaut — o núcleo é um
> processo long-running com injeção manual no composition root
> ([Componentes §6](../architecture/components.md#6-version-catalog)). O
> `JavaAgent` opera sobre Java puro e Gradle.

### JavaFxAgent

| Campo | Valor |
|---|---|
| Responsável por | UI, UX, componentes, event handling, system tray, integração desktop |
| Teto | YELLOW |
| Efeitos | READ_FS, WRITE_FS (apenas `zordon-desktop/`) |
| SPECs obrigatórias | [UI](../specs/ui/design.md) |
| **Não faz** | Não coloca regra de negócio em controller ([padrões §9](../process/code-standards.md#9-testabilidade)) |

### WindowsAgent · WslAgent

| Campo | Valor |
|---|---|
| Responsável por | `WindowsAgent`: `zordon-host`, bridge, tarefas agendadas, MSI. `WslAgent`: systemd, rede, interop, montagem, empacotamento Linux |
| Teto | YELLOW |
| SPECs obrigatórias | [Windows↔WSL](../architecture/windows-wsl.md) |
| **Não faz** | Nenhum dos dois assume que o usuário do Windows e o do WSL têm o mesmo nome ([§R21](../architecture/windows-wsl.md#r21--o-usuário-do-windows-não-é-o-usuário-do-wsl)) |

### SecurityAgent

| Campo | Valor |
|---|---|
| Responsável por | Security review, segredos, modelo de permissão, threat modeling, prompt injection, cadeia de suprimentos |
| Teto | YELLOW |
| Efeitos | READ_FS, WRITE_FS (`zordon-security/`, `docs/security/`) |
| Revisão obrigatória em | As categorias de [DoD §6](../process/definition-of-done.md#6-quando-a-revisão-de-segurança-é-obrigatória) |
| **Não faz** | **Não altera `SecurityPolicy`.** Nem ele. A política só muda pelo usuário, no sistema de arquivos, com reinício |

### DefenseAgent

| Campo | Valor |
|---|---|
| Responsável por | Exclusivamente o Zordon Defense: detectores, playbooks, quarentena, disjuntor, lockdown |
| Teto | YELLOW |
| Efeitos | READ_FS, WRITE_FS (`zordon-defense/`) |
| **Não faz** | Não implementa detecção que dependa do LLM para decidir ([ADR-0016](../adr/ADR-0016-defesa-deterministica.md)) |

### McpAgent · RagAgent · VoiceAgent · AutomationAgent · DatabaseAgent

Agentes de módulo. Cada um: teto YELLOW, escrita restrita ao próprio módulo, SPEC
do módulo obrigatória, e proibição de tocar módulo alheio — integração
atravessando módulos passa pelo `ArchitectureAgent`.

| Agente | Módulo | SPEC obrigatória |
|---|---|---|
| `McpAgent` | `zordon-mcp` | [MCP](../specs/mcp/design.md) |
| `RagAgent` | `zordon-rag` | [Knowledge Base](../rag/knowledge-base.md) |
| `VoiceAgent` | `zordon-voice` | [Voz](../specs/voice/design.md) |
| `AutomationAgent` (eng.) | `zordon-automation` | [Automação](../specs/automation/design.md) |
| `DatabaseAgent` | `zordon-memory` | [Memória](../specs/memory/design.md) |

### TestingAgent

| Campo | Valor |
|---|---|
| Responsável por | Testes de unidade, integração, regressão, aceite e segurança |
| Teto | YELLOW |
| Efeitos | READ_FS, WRITE_FS (apenas `**/src/test/**`), SPAWN_PROCESS |
| SPECs obrigatórias | [Estratégia de testes](../testing/strategy.md) |
| **Não faz** | **Não altera código de produção para fazer teste passar.** Se o teste falha, ele reporta; quem corrige é o agente de domínio |

A proibição acima é a mais importante do catálogo. Um agente de teste com
permissão de editar produção converge para "ajustar a asserção até passar", que é
a pior falha possível em um sistema onde ninguém está olhando.

### CodeReviewAgent

| Campo | Valor |
|---|---|
| Responsável por | Clean Code, SOLID, code smells, segurança, performance, complexidade, duplicação, testes, arquitetura |
| Teto | **GREEN** |
| Efeitos | READ_FS apenas |
| SPECs obrigatórias | [Padrões de código](../process/code-standards.md) |
| **Não implementa** | Somente revisa. Produz achados com proposta de refatoração |

Teto **GREEN e somente leitura** é deliberado: um revisor que pode alterar o que
revisa não é um revisor. Ele produz o achado; a correção volta ao agente de
domínio, e o ciclo se repete.

### DevOpsAgent

| Campo | Valor |
|---|---|
| Responsável por | Gradle, CI, empacotamento, systemd, MSI, release |
| Teto | YELLOW |
| Efeitos | READ_FS, WRITE_FS (`packaging/`, `.github/`, arquivos de build), SPAWN_PROCESS |
| **Não faz** | Não altera `.github/workflows/` sem revisão do `SecurityAgent` — alterar o CI é alterar quem verifica ([cadeia de suprimentos §3](../security/supply-chain.md#3-pipeline-obrigatória-de-pr)) |

## 6. Matriz de capacidades

| Agente | Escrita | Executa | Rede | Docs | Teto |
|---|:---:|:---:|:---:|:---:|:---:|
| `architecture` | `docs/` | — | — | restricted | YELLOW |
| `spec` | `docs/specs/` | — | — | restricted | YELLOW |
| `documentation` | `docs/` | — | — | restricted | YELLOW |
| `java` | código | build | — | internal | YELLOW |
| `javafx` | `zordon-desktop/` | build | — | internal | YELLOW |
| `windows` / `wsl` | módulo | build | — | internal | YELLOW |
| `security` | `zordon-security/`, `docs/security/` | — | — | restricted | YELLOW |
| `defense` | `zordon-defense/` | — | — | restricted | YELLOW |
| `mcp`/`rag`/`voice`/`automation`/`database` | módulo | build | — | internal | YELLOW |
| `testing` | `src/test/` | testes | — | internal | YELLOW |
| `codereview` | **—** | — | — | restricted | **GREEN** |
| `devops` | build, packaging | build | — | internal | YELLOW |
| `research` (assist.) | **—** | — | ✓ | public | **GREEN** |

## 7. Alteração do próprio projeto

Agentes do perfil `ENGINEERING` alteram o repositório do Zordon. Isso exige, além
do `allowedPaths`, o preflight de
[Auto-modificação §6](../process/self-modification.md#6-preflight-obrigatório) e
o efeito correspondente:

| Efeito | Quem pode declarar | Classificação |
|---|---|---|
| `MODIFY_SELF` | Agentes `ENGINEERING`, no próprio repositório | YELLOW |
| `MODIFY_PROJECT` | Agentes de projeto (`projeto-<nome>`), em `workspaces` | YELLOW |
| `MODIFY_TRUST_KERNEL` | **Nenhum agente aplica.** `SecurityAgent` e `DefenseAgent` apenas **propõem** via PR | **RED** |

Nenhum agente — incluindo `SecurityAgent` — tem escrita nos diretórios de
instalação do Zordon. Escrever código e instalá-lo são coisas diferentes, e só a
primeira é do Zordon ([ADR-0024](../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md)).

## 8. Adicionar um agente

Criar `~/.zordon/agents/<id>.toml` (runtime) ou `docs/agents/definitions/<id>.toml`
(engenharia). Zero código.

Se adicionar um agente exigir alterar `zordon-core`, o ponto de extensão está
faltando — e o conserto é criar o ponto de extensão, não abrir exceção.

## 9. Riscos

| Risco | Mitigação |
|---|---|
| Agentes demais para uma tarefa simples | O orquestrador seleciona o mínimo ([Orquestração §4](orchestration.md#4-seleção-de-agentes)) |
| Escopo de agente cresce até virar "faz tudo" | A coluna "**não faz**" de cada agente é normativa e revisada |
| `TestingAgent` ajustando produção | Proibição estrutural: sem `WRITE_FS` fora de `src/test/` |
| `CodeReviewAgent` complacente | Achados bloqueantes são categorias fechadas ([padrões §4](../process/code-standards.md#4-code-smells)) |
| Agente de engenharia agindo na máquina do usuário | Perfil declarado; `allowedPaths` restrito ao repositório |

## 10. Critérios de aceite

- `CA-1` Nenhum agente é carregado sem `securityScope` e `tokenBudget` declarados.
- `CA-2` Ferramenta que produz efeito fora de `allowedEffects` não é oferecida ao
  modelo daquele agente.
- `CA-3` `TestingAgent` não consegue escrever fora de `src/test/`.
- `CA-4` `CodeReviewAgent` não consegue escrever em lugar nenhum.
- `CA-5` Criar um agente novo não exige alteração em `zordon-core`.
- `CA-6` Nenhum agente tem escrita nos diretórios de instalação do Zordon.
- `CA-7` `MODIFY_TRUST_KERNEL` nunca resulta em alteração aplicada, apenas em PR.
