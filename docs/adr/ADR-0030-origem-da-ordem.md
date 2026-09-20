---
document: adr-0030
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,seguranca,identidade,origem,voz]
specId: null
---

# ADR-0030 — A origem da ordem limita o risco que ela pode autorizar

**Status:** Aceito · 2026-09-18

## Contexto

O modelo de confiança assume um único usuário local
([Visão §8](../vision.md)), e a identidade existe para componentes
([Defesa §2](../security/defense.md#2-zero-trust-aplicado)). Falta dizer **quem deu
a ordem**. Com a voz no ar ([SPEC-011](../specs/voice/SPEC-011-motor-de-voz-ouvir-e-falar.md)),
qualquer pessoa na sala — ou um vídeo tocando — pode falar "Zordon, apague a
pasta". Uma ordem escrita na janela, com a sessão do Windows aberta, tem outra
força. Automações e agentes também dão ordens, sem ninguém presente.

## Alternativas

| Alternativa | Por que não |
|---|---|
| Tratar toda ordem igual | Voz vira o caminho mais fraco para ação destrutiva |
| Verificação de locutor (biometria de voz) agora | Cara, falha com ruído e gripe, e dá falsa segurança; pode vir depois como sinal, não como porta |
| **Origem como atributo obrigatório, com teto de risco por origem** | — |

## Decisão

Toda ação carrega `origin`: `ui` (digitada ou clicada na janela), `voice`,
`automation`, `agent` (delegação) ou `autonomous` (defesa, reação a evento). A
origem define o **teto** do que a ordem autoriza sozinha:

| Origem | GREEN | YELLOW | RED |
|---|---|---|---|
| `ui` | executa | conforme política | diálogo na tela |
| `voice` | executa | confirmação **na tela** | diálogo na tela; nunca "sim" falado |
| `automation` | executa | só se a automação foi aprovada com esse escopo | nunca; notifica e aguarda |
| `agent` | herda a origem de quem iniciou a tarefa, nunca mais que ela | idem | idem |
| `autonomous` | só contenção reversível ([ADR-0016](ADR-0016-defesa-deterministica.md)) | — | — |

`OPERATOR` continua sendo o usuário na janela; é o único que sai do lockdown e
altera política. Detalhes em [Identidade e origem](../security/identity.md).

## Consequências

- O `PermissionEngine` recebe `origin` junto com a ação; o `AuditLog` o registra.
- "Confirmar por voz" não existe para YELLOW e RED: a confirmação é visual.
- Delegação não escala privilégio: um agente chamado por uma automação tem o teto
  da automação.
- Verificação de locutor, se vier, eleva a confiança de `voice` para leitura, mas
  não substitui a confirmação na tela.
