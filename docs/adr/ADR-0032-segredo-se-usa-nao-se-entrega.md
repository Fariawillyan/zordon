---
document: adr-0032
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,seguranca,segredos,agentes]
specId: null
---

# ADR-0032 — Segredo se usa, não se entrega

**Status:** Aceito · 2026-09-18

## Contexto

[Segurança §5](../security/model.md#5-segredos) define onde os segredos ficam
(Credential Manager, `secrets.age`) e como são mascarados. Não define como um
agente usa uma credencial: fazer `git push` com a chave SSH, chamar uma API com
um token. Se o agente receber o valor, ele entra no contexto do modelo, pode
sair num log, num prompt, numa resposta — e um prompt injection pode pedi-lo.

## Alternativas

| Alternativa | Por que não |
|---|---|
| Variável de ambiente no processo do agente | O valor fica legível para tudo que o agente executa |
| Mascarar na saída | Redação é rede de proteção, não controle; o modelo já viu |
| **Uso intermediado: o agente pede a operação; o `SecretBroker` a executa com o segredo** | — |

## Decisão

Agentes e skills referenciam segredos por nome (`secret:github-token`,
`secret:ssh/work`), nunca por valor. O `SecretBroker` (`zordon-security`):

- injeta o valor só no processo que precisa, dentro do sandbox
  ([ADR-0031](ADR-0031-sandbox-para-codigo-de-agente.md)): `GIT_SSH_COMMAND` com um
  agente SSH temporário, cabeçalho HTTP montado pelo próprio broker;
- nunca devolve o valor ao chamador, ao modelo, ao log ou ao evento;
- registra cada uso no `AuditLog`: qual segredo, qual ação, qual origem;
- aplica escopo por segredo: quais agentes, quais destinos, qual origem máxima
  ([ADR-0030](ADR-0030-origem-da-ordem.md)).

A chave SSH do usuário nunca é copiada: o broker usa o `ssh-agent` do Windows via
host, ou uma chave dedicada ao Zordon, cadastrada pelo usuário.

## Consequências

- Um segredo sem escopo declarado não pode ser usado por nenhum agente.
- Prompt injection que "pede o token" não tem o que receber.
- O M1 (`secrets.env`) continua provisório; a migração para o broker acontece no
  M3, junto com o `PermissionEngine`.
