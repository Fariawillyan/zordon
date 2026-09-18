---
document: specs-security-index
module: security
section: index
version: 1
updatedAt: 2026-09-17
securityLevel: restricted
tags: [specs,seguranca,indice]
specId: null
---

# SPECs — segurança

A **norma** de segurança não vive aqui. Ela vive em
[`docs/security/`](../../security/), que é a autoridade:

| Documento | Conteúdo |
|---|---|
| [Modelo](../../security/model.md) | Ameaças, classificação de risco, permissão, auditoria, segredos, injeção |
| [Defesa](../../security/defense.md) | Zero Trust, detectores, playbooks, quarentena, disjuntor, lockdown |
| [Comunicação](../../security/communication.md) | Nenhuma ação silenciosa, níveis de alerta, anti-fadiga |
| [Cadeia de suprimentos](../../security/supply-chain.md) | Apache 2.0, pipeline de PR, dependências, divulgação |

Esta pasta guarda apenas **SPECs de funcionalidades de segurança** —
implementações concretas que derivam daquela norma.

Toda SPEC aqui exige revisão do `SecurityAgent` e do `ArchitectureAgent`, e
nenhuma pode relaxar uma das
[cinco invariantes](../../architecture/security.md#2-as-cinco-invariantes).

| ID | Nome | Status |
|---|---|---|
| — | _nenhuma ainda_ | — |
