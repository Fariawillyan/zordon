---
document: index
module: meta
section: index
version: 3
updatedAt: 2026-09-17
securityLevel: public
tags: [indice,navegacao,mapa]
specId: null
---

# Documentação do Zordon

> **A documentação é a fonte de verdade do projeto.** Ela não descreve o que foi
> construído — ela governa o que será. E alimenta o [RAG](rag/knowledge-base.md),
> que alimenta os agentes que constroem o Zordon.
>
> **Documentação não é subproduto do código. Ela faz parte da implementação.**
> Uma tarefa termina com `SPEC + implementação + testes + segurança +
> documentação + RAG + auditoria`. Faltando um, ela está incompleta.

Documentos marcados com ★ são pré-requisito para começar a implementação.

## Comece aqui

| # | Documento | Por quê |
|---|---|---|
| 1 | [Visão e escopo](vision.md) ★ | O que é, o que não é, princípios, requisitos |
| 2 | [Arquitetura](architecture/overview.md) ★ | Processos, camadas, fluxo de um turno |
| 3 | [Windows ↔ WSL](architecture/windows-wsl.md) ★ | Riscos medidos. **Leia antes de codar** |
| 4 | [Spec-Driven Development](process/spec-driven-development.md) ★ | Como uma ideia vira funcionalidade |
| 5 | [Roadmap](roadmap.md) ★ | M0 a M7 |

## Processo

| Documento | Responde |
|---|---|
| [Spec-Driven Development](process/spec-driven-development.md) ★ | O fluxo obrigatório; quando a SPEC é exigida |
| [Template de SPEC](process/spec-template.md) | O formato, e como escrever critério de aceite |
| [Definition of Done](process/definition-of-done.md) ★ | Os dez portões. Compilar não é concluir |
| [Auto-modificação](process/self-modification.md) ★ | Como o Zordon altera o próprio projeto e os de terceiros |
| [Padrões de código](process/code-standards.md) ★ | Clean Code, SOLID, smells, complexidade, naming, comentários |
| [Rastreabilidade](process/traceability.md) | SPEC ↔ commit ↔ código ↔ teste ↔ doc |

## Arquitetura

| Documento | Responde |
|---|---|
| [Visão geral](architecture/overview.md) ★ | Três processos, camadas, fluxo de um turno |
| [Componentes e build](architecture/components.md) ★ | Módulos, Gradle, regras verificadas por ArchUnit |
| [Comunicação](architecture/communication.md) | Quem fala com quem, e por qual transporte |
| [Orientação a eventos](architecture/event-driven.md) | Barramento, tópicos, backpressure, replay |
| [Segurança (mapa)](architecture/security.md) | Onde a segurança se encaixa; qual doc responde o quê |
| [Windows ↔ WSL](architecture/windows-wsl.md) ★ | 26 riscos, com medições desta máquina |

## Contratos

| Documento | Responde |
|---|---|
| [ZWP — protocolo](api/zwp-protocol.md) ★ | Handshake, métodos, eventos, frames binários, erros |
| [Interfaces do núcleo](api/core-interfaces.md) ★ | `AiProvider`, `ZordonSkill`, `PermissionEngine`, `MemoryStore`… |

## Segurança

A norma. Três documentos que se leem juntos.

| Documento | Responde |
|---|---|
| [Modelo](security/model.md) ★ | Ameaças, classificação de risco, auditoria, segredos, prompt injection |
| [Defesa e detecção](security/defense.md) ★ | Zero Trust, detectores, playbooks, quarentena, disjuntor, lockdown |
| [Comunicação proativa](security/communication.md) ★ | Nenhuma ação silenciosa: níveis, canais, anti-fadiga |
| [Cadeia de suprimentos](security/supply-chain.md) | Apache 2.0, pipeline de PR, dependências, divulgação |

## Agentes e conhecimento

| Documento | Responde |
|---|---|
| [Catálogo de agentes](agents/catalog.md) | Os agentes, seus escopos, e o que cada um **não** faz |
| [Orquestração](agents/orchestration.md) | Seleção, delegação, limites, ciclo de revisão |
| [Runtime de agente](specs/agents/design.md) | Laço, orçamento, disjuntor, delegação |
| [Knowledge Base](rag/knowledge-base.md) | O que é indexado, com que metadados, e o que não é |
| [Indexação](rag/indexing.md) | Chunking, incremental, versionamento |
| [Context Router](rag/context-router.md) | Como o agente recebe só o que precisa |

## Especificações por módulo

| Módulo | Design |
|---|---|
| [core](specs/core/design.md) | IA, providers, Intent Router, contexto, custo |
| [voice](specs/voice/design.md) | Wake word, VAD, STT, TTS, latência |
| [agents](specs/agents/design.md) | Runtime de agente |
| [mcp](specs/mcp/design.md) | Cliente MCP, ToolRegistry, seleção semântica |
| [memory](specs/memory/design.md) | Três níveis, SQLite, recuperação híbrida |
| [automation](specs/automation/design.md) | Scheduler, watchers, workflows |
| [ui](specs/ui/design.md) | JavaFX, tray, overlay, design system |

[Índice de SPECs](specs/README.md)

## Design desktop inicial

Baseline **DRAFT**, com referência visual e regras para as futuras SPECs JavaFX.

| Documento | Responde |
|---|---|
| [Zordon Control — design system](specs/ui/design-system.md) | Identidade, cores verificadas, tipografia, componentes e acessibilidade |
| [Layout desktop](specs/ui/desktop-layout.md) | Regiões, navegação, medidas, fluxos, estados e adaptação à janela |
| [Fundamentos e pendências](specs/ui/design-review.md) | Relação com o acervo e lacunas de contratos antes da implementação |
| [Prévia navegável](specs/ui/preview/index.html) | Referência local com dados fictícios e cenários de interação |

## Operação e qualidade

| Documento | Responde |
|---|---|
| [Primeiros passos](operations/quickstart.md) ★ | Do zero à primeira resposta, para quem vai usar |
| [Instalação e runbook](operations/install.md) | `.wslconfig`, systemd, autostart, troubleshooting |
| [Observabilidade](operations/observability.md) | Logs, métricas, tracing, Diagnostics, SLOs |
| [Uso de tokens](operations/token-usage.md) | Medição, orçamento, dashboard, otimização |
| [Estratégia de testes](testing/strategy.md) | Pirâmide, aceite, segurança, golden, fakes |

## Referência

| Documento | |
|---|---|
| [Decisões arquiteturais](adr/README.md) | 24 ADRs registrados |
| [Glossário](glossary.md) | Termos do projeto |

## Governança (raiz do repositório)

| Arquivo | |
|---|---|
| [LICENSE](../LICENSE) | Apache License 2.0 |
| [SECURITY.md](../SECURITY.md) | Reportar vulnerabilidade: escopo e prazos |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Invariantes que reprovam PR |
| [CODEOWNERS](../CODEOWNERS) | Revisão obrigatória por caminho |

---

## As cinco invariantes

Atravessam todos os documentos e não admitem exceção pontual:

1. **Não existe execução de shell arbitrária.** ([ADR-0007](adr/ADR-0007-permissao-sobre-acao-estruturada.md))
2. **O Zordon não apaga arquivos.** ([ADR-0015](adr/ADR-0015-exclusao-impossivel-por-construcao.md))
3. **Nenhuma iniciativa autônoma é silenciosa.** ([ADR-0014](adr/ADR-0014-nenhuma-iniciativa-silenciosa.md))
4. **O LLM nunca decide segurança.** ([ADR-0016](adr/ADR-0016-defesa-deterministica.md))
5. **O Zordon não altera o próprio núcleo de confiança** em execução, nem sem
   revisão humana. ([ADR-0024](adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md))

Qualquer proposta que precise violar uma delas está propondo outro produto.

## A hierarquia de autoridade

Quando duas fontes discordam, vence a de cima:

```text
Security Policy  >  SPEC aprovada  >  Arquitetura/ADR  >  contexto RAG  >  raciocínio do agente
```

## Convenções

- **Deve / não deve** indicam requisito. **Pode / recomenda-se** indicam opção.
- Todo documento tem **front-matter** com os metadados do RAG. Ausência reprova o
  build ([ADR-0023](adr/ADR-0023-documentacao-como-fonte-de-verdade.md)).
- Blocos `text` são diagramas; blocos `java`/`json`/`sql` são contratos que a
  implementação precisa honrar.
- **Um conceito tem um dono.** Os outros documentos referenciam por link. Não
  duplicar — duas cópias divergem.
- Toda decisão com alternativa razoável descartada vira ADR. Se uma decisão se
  mostrar errada, o caminho é **um ADR novo que supera o anterior**, nunca editar
  o antigo em silêncio.
- Documentação e texto ao usuário em português; código e identificadores em
  inglês.
