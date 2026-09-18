---
document: spec-driven-development
module: process
section: sdd
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [spec,processo,fluxo,review,aceite]
specId: null
---

# Spec-Driven Development

## 1. Objetivo

Garantir que toda funcionalidade relevante do Zordon exista primeiro como
documento revisável, e só depois como código.

## 2. Contexto

O Zordon é construído em grande parte por agentes de IA
([Catálogo de agentes](../agents/catalog.md)). Um agente é excelente em produzir
código plausível e péssimo em descobrir sozinho qual é o problema certo. Sem uma
especificação, ele preenche as lacunas com suposições — e suposições diferentes a
cada execução.

A SPEC resolve isso em três frentes:

1. **Determina o alvo.** O agente implementa o que está escrito, não o que ele
   imagina.
2. **Torna o desacordo barato.** Discordar de um parágrafo custa minutos;
   discordar de uma implementação custa dias.
3. **Vira teste.** Critérios de aceite bem escritos são casos de teste
   disfarçados ([§6](#6-specs-executáveis)).

## 3. O fluxo

```text
   IDEIA
     │  o usuário quer algo; ainda é uma frase
     ▼
   SPEC ─────────────── SpecAgent escreve
     │  objetivo, escopo, interfaces, eventos, segurança, aceite
     ▼
   REVIEW ───────────── ArchitectureAgent + SecurityAgent
     │  cabe na arquitetura? viola alguma invariante?
     ▼
   PLANO ───────────── ZordonOrchestrator
     │  quais agentes, em que ordem, com que orçamento
     ▼
   IMPLEMENTAÇÃO ───── agentes de domínio (Java, JavaFx, Mcp, Voice…)
     │
     ▼
   TESTES ──────────── TestingAgent
     │  critérios de aceite viram testes automatizados
     ▼
   VALIDAÇÃO ───────── CodeReviewAgent + SecurityAgent quando aplicável
     │
     ▼
   DOCUMENTAÇÃO ────── DocumentationAgent + reindexação do RAG
```

Cada seta é um portão. Não se pula portão por urgência: se o processo é lento
demais para uma urgência, o problema é o processo.

### Quando a tarefa altera um projeto

Se a tarefa vai **modificar código** — o próprio Zordon ou um projeto de
terceiro —, o fluxo ganha um passo de conhecimento na frente:

```text
IDEIA → RAG → SPEC → ADR (se necessário) → PLANO → AGENTS → IMPLEMENTAÇÃO
```

O `RAG` antes da SPEC não é decoração: escrever uma SPEC sem antes consultar o
que já foi decidido produz SPECs que contradizem ADRs existentes. Os nove passos
obrigatórios desse preflight estão em
[Auto-modificação §6](self-modification.md#6-preflight-obrigatório), e o
princípio que os governa é curto: **nunca modificar um projeto silenciosamente.**

## 4. Quando uma SPEC é obrigatória

| Precisa de SPEC | Não precisa |
|---|---|
| Módulo novo | Correção de bug com causa conhecida |
| Interface pública nova ou alterada | Refatoração sem mudança de comportamento |
| Evento ZWP novo | Ajuste de texto, log ou mensagem |
| Skill, agente ou detector novo | Atualização de dependência |
| Mudança em permissão, risco ou política | Formatação, renomeação local |
| Mudança em esquema de dados | Teste adicional para código existente |
| Qualquer coisa que toque uma [invariante](../README.md#as-cinco-invariantes) | |

Na dúvida, escreva a SPEC. Uma SPEC de 20 linhas que se mostra desnecessária
custou 20 minutos; uma implementação sem SPEC que se mostra errada custa a
semana.

## 5. Estados de uma SPEC

```text
   DRAFT ──► REVIEW ──► APPROVED ──► IMPLEMENTING ──► DONE
                │                          │
                └──► REJECTED              └──► BLOCKED
```

| Estado | Significa |
|---|---|
| `DRAFT` | Em escrita. Ninguém implementa |
| `REVIEW` | Aguardando revisão de arquitetura e, quando aplicável, segurança |
| `APPROVED` | Pode ser implementada. **É este o estado que um agente exige antes de codar** |
| `IMPLEMENTING` | Em execução, com `runId` associado |
| `DONE` | Atende à [Definition of Done](definition-of-done.md) |
| `BLOCKED` | Depende de outra SPEC ou de decisão pendente |
| `REJECTED` | Descartada. **Não é apagada** — o registro do que não fizemos tem valor |

Uma SPEC `REJECTED` ou superada nunca é removida do repositório, pela mesma razão
que um [ADR](../adr/README.md) aceito nunca é editado.

## 6. Specs executáveis

Critérios de aceite devem ser escritos de forma que virem teste quase
mecanicamente.

**Ruim** — não é verificável:

```text
O sistema deve reconectar rapidamente.
```

**Bom** — é um teste:

```text
CA-3  Dado que o núcleo reiniciou e o cliente tinha lastEventSeq=48210,
      quando o cliente reconecta em até 5 minutos,
      então o handshake responde resumed=false com startId novo
      e o cliente recarrega via chat.history.
```

Cada critério de aceite recebe um identificador (`CA-1`, `CA-2`…) e o teste que o
valida referencia esse identificador:

```java
@AcceptanceCriteria("SPEC-014/CA-3")
@Test
void reconexaoAposReinicioDoNucleoNaoAssumeContinuidade() { ... }
```

Isso permite responder mecanicamente "quais testes validam a SPEC-014?" —
ver [Rastreabilidade](traceability.md).

## 7. Responsabilidades

| Papel | Responsabilidade |
|---|---|
| Usuário | Traz a ideia, aprova a SPEC, decide escopo |
| `SpecAgent` | Escreve e revisa SPECs; detecta requisito faltante; define critérios de aceite |
| `ArchitectureAgent` | Revisa encaixe arquitetural, boundaries, dependências |
| `SecurityAgent` | Revisa toda SPEC que toque permissão, dado sensível, rede ou execução |
| `ZordonOrchestrator` | Monta o plano e o orçamento |
| Agentes de domínio | Implementam **exatamente** o que a SPEC aprovada descreve |
| `TestingAgent` | Converte critérios de aceite em testes |
| `CodeReviewAgent` | Valida qualidade e aderência à SPEC |
| `DocumentationAgent` | Atualiza documentação afetada e reindexa o RAG |

## 8. Dependências

- [Template de SPEC](spec-template.md) — formato obrigatório
- [Auto-modificação](self-modification.md) — preflight para alterar projetos
- [Definition of Done](definition-of-done.md) — portão de saída
- [Catálogo de agentes](../agents/catalog.md) — quem faz o quê
- [Context Router](../rag/context-router.md) — como a SPEC chega ao agente
- [Índice de SPECs](../specs/README.md)

## 9. Riscos

| Risco | Mitigação |
|---|---|
| SPEC vira burocracia e ninguém escreve | A tabela de [§4](#4-quando-uma-spec-é-obrigatória) é curta e específica; bug fix não exige SPEC |
| SPEC desatualizada em relação ao código | `DocumentationAgent` automático ([§26 do processo](../agents/catalog.md#documentationagent)) e rastreabilidade bidirecional |
| SPEC grande demais para revisar | Teto de uma tela de escopo; acima disso, divide-se em SPECs dependentes |
| Agente implementa além da SPEC | `CodeReviewAgent` reprova mudança fora do escopo declarado |
| SPEC aprovada com falha de segurança | Revisão do `SecurityAgent` é obrigatória, não opcional, para as categorias de [§7](#7-responsabilidades) |

## 10. Segurança

Uma SPEC é **conteúdo**, não autoridade. A hierarquia é:

```text
Security Policy  >  SPEC aprovada  >  Arquitetura/ADR  >  contexto RAG  >  raciocínio do agente
```

Uma SPEC não pode conceder permissão, relaxar classificação de risco nem criar
exceção à política. Se uma SPEC precisa disso, ela precisa antes de um
[ADR](../adr/README.md) e de alteração da política pelo usuário no sistema de
arquivos — ver [Defesa §2](../security/defense.md#2-zero-trust-aplicado).

## 11. Testes

O processo em si é verificado no build:

| Verificação | Reprova quando |
|---|---|
| Toda SPEC tem os campos obrigatórios do template | Campo ausente ou vazio |
| Todo `@AcceptanceCriteria` referencia SPEC e critério existentes | Referência órfã |
| Toda SPEC `DONE` tem ao menos um teste ligado | SPEC marcada pronta sem teste |
| Todo `specId` citado em front-matter existe | Referência quebrada |

## 12. Critérios de aceite

- `CA-1` Uma funcionalidade nova das categorias de [§4](#4-quando-uma-spec-é-obrigatória)
  não entra em `main` sem SPEC em estado `APPROVED` ou posterior.
- `CA-2` Um agente que recebe tarefa de implementação recusa executar se a SPEC
  referenciada não estiver `APPROVED`.
- `CA-3` Todo critério de aceite de SPEC `DONE` tem teste automatizado ligado por
  `@AcceptanceCriteria`.
- `CA-4` O comando de rastreabilidade responde "quais arquivos e testes
  implementam a SPEC-XXX" sem intervenção manual.
