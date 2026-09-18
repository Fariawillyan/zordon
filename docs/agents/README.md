---
document: agents-index
module: agents
section: index
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [agentes,indice,registry,orquestracao]
specId: null
---

# Agentes

| Documento | Responde |
|---|---|
| [Catálogo](catalog.md) | Quais agentes existem, o que cada um faz e o que não faz |
| [Orquestração](orchestration.md) | Como são selecionados, colaboram e são limitados |
| [Runtime](../specs/agents/design.md) | Como um agente executa: laço, orçamento, disjuntor |

## Duas populações, um mecanismo

O Zordon tem agentes de dois **perfis**, sobre o mesmo `AgentRegistry`, o mesmo
orquestrador, os mesmos tetos de permissão e os mesmos orçamentos:

| Perfil | Servem a | Exemplos |
|---|---|---|
| **Assistente** | O usuário, no dia a dia da máquina dele | `system`, `developer`, `research`, `automation`, `projeto-<nome>` |
| **Engenharia** | A construção do próprio Zordon | `architecture`, `spec`, `documentation`, `java`, `javafx`, `testing`, `codereview`, … |

O perfil de engenharia é o Zordon sendo usado para construir o Zordon. Não é um
sistema paralelo: é o mesmo produto, com definições de agente diferentes.
Ver [ADR-0021](../adr/ADR-0021-dois-perfis-de-agente.md).

## A regra que governa todos

> **Nenhum agente tem acesso a tudo.** Least privilege, sempre.

Um agente é uma configuração, não uma classe
([Runtime §1](../specs/agents/design.md#1-o-que-é-um-agente-aqui)). Adicionar
agente é criar um arquivo, nunca recompilar o núcleo.
