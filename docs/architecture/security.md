---
document: architecture-security
module: architecture
section: security
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [seguranca,indice,invariantes,camadas]
specId: null
---

# Arquitetura de segurança

Este documento é o **mapa**. A norma vive em [`docs/security/`](../security/) e
não é repetida aqui.

## 1. Objetivo

Mostrar onde a segurança se encaixa na arquitetura e qual documento responde o
quê.

## 2. As cinco invariantes

| Invariante | Consequência | Norma |
|---|---|---|
| Sem execução de shell arbitrária | Injeção de shell é impossível, não improvável | [ADR-0007](../adr/ADR-0007-permissao-sobre-acao-estruturada.md) |
| O Zordon não apaga arquivos | Quarentena reversível; dano irreversível não existe | [ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md) |
| Nenhuma iniciativa autônoma é silenciosa | `DETECTAR → COMUNICAR → AGIR → COMUNICAR → REGISTRAR` | [ADR-0014](../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md) |
| O LLM nunca decide segurança | Ele explica e recomenda; o código decide | [ADR-0016](../adr/ADR-0016-defesa-deterministica.md) |
| O Zordon não altera o próprio núcleo de confiança | A segurança do sistema deixa de ser circular | [ADR-0024](../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md) |

**A quinta existe por causa das quatro primeiras.** Elas são garantidas por
código; se o Zordon pudesse editar esse código, todas viriam a ser sugestões.
Ver [Auto-modificação §2](../process/self-modification.md#2-o-paradoxo-que-este-documento-resolve).

## 3. Camadas

```text
┌──────────────────────────────────────────────────────────┐
│ DEFESA         DetectionEngine · DefenseEngine · Breaker │  security/defense.md
├──────────────────────────────────────────────────────────┤
│ CONTROLE       PermissionEngine · CommandValidator       │  security/model.md
│                SecurityPolicy (imutável em execução)     │
├──────────────────────────────────────────────────────────┤
│ EXECUÇÃO       ProcessExec · FileAccess · WindowsBridge  │
├──────────────────────────────────────────────────────────┤
│ TRANSVERSAL    NotificationCenter · AuditLog · Secrets   │  security/communication.md
└──────────────────────────────────────────────────────────┘
```

Regra: **a camada de CONTROLE é intransponível**, e a de DEFESA também passa por
ela. Uma ação de contenção é classificada como qualquer outra; a política apenas
concede autorização prévia (`AutoContain`) ao subconjunto reversível e com prazo.

Verificado por ArchUnit ([Componentes §4](components.md#4-regras-de-dependência-verificadas-no-build)).

## 4. Qual documento responde o quê

| Pergunta | Documento |
|---|---|
| Quem é o atacante e o que protegemos? | [Segurança §1](../security/model.md#1-modelo-de-ameaças) |
| Esta ação pode executar? | [Segurança §2](../security/model.md#2-classificação-de-risco) |
| Como um segredo é tratado? | [Segurança §5](../security/model.md#5-segredos) |
| Como nos defendemos de prompt injection? | [Segurança §6](../security/model.md#6-prompt-injection) |
| Como detectamos comportamento hostil? | [Defesa §4](../security/defense.md#4-detection-engine) |
| O que acontece quando detectamos? | [Defesa §5](../security/defense.md#5-defense-engine-e-playbooks) |
| Como o usuário fica sabendo? | [Comunicação](../security/communication.md) |
| Como tratamos código de terceiros? | [Cadeia de suprimentos](../security/supply-chain.md) |
| Até onde a proteção vai? | [ADR-0017](../adr/ADR-0017-zordon-nao-e-antivirus.md) |
| O Zordon pode se alterar? | [Auto-modificação](../process/self-modification.md) |

## 5. Segurança no processo

| Momento | Controle |
|---|---|
| SPEC | Seção 10 obrigatória; `SecurityAgent` revisa nas categorias de [DoD §6](../process/definition-of-done.md#6-quando-a-revisão-de-segurança-é-obrigatória) |
| Implementação | `allowedEffects` do agente limita o que ele alcança |
| Revisão | Achados de segurança são bloqueantes |
| Build | ArchUnit, golden de permissão, SAST, secret scan |
| Merge | CODEOWNERS obrigatório em `zordon-security/` e `zordon-defense/` |
| Runtime | `PermissionEngine`, `DefenseEngine`, `AuditLog` |

## 6. Critérios de aceite

- `CA-1` Toda invariante de §2 tem teste que tenta violá-la
  ([testes §5](../testing/strategy.md#5-testes-de-segurança)).
- `CA-2` Nenhuma camada acima de EXECUÇÃO a alcança sem passar por CONTROLE.
- `CA-3` Toda SPEC das categorias de risco tem revisão de segurança registrada.
