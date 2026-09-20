---
document: security-identity
module: security
section: identity
version: 1
updatedAt: 2026-09-18
securityLevel: internal
tags: [seguranca,identidade,origem,voz,permissao,operator]
specId: null
---

# Identidade e origem das ordens

Quem pode mandar o Zordon fazer o quê. Decisão em
[ADR-0030](../adr/ADR-0030-origem-da-ordem.md).

## 1. Princípio

O Zordon tem **um usuário** ([Visão §8](../vision.md)), mas recebe ordens por
caminhos com confiança diferente. A identidade relevante não é "quem é a pessoa",
e sim **por onde a ordem entrou** e **quem a iniciou**. Isso é a `origin`, e ela
viaja com toda ação até o `PermissionEngine` e o `AuditLog`.

## 2. Origens

| Origem | Entra por | Confiança | Por quê |
|---|---|---|---|
| `ui` | Janela do desktop: texto, clique, diálogo | Alta | Exige a sessão do Windows aberta e a janela em foco |
| `voice` | Microfone, pela escuta ([SPEC-011](../specs/voice/SPEC-011-motor-de-voz-ouvir-e-falar.md)) | Baixa para efeitos | Qualquer pessoa na sala, uma chamada de vídeo ou um vídeo tocando pode falar |
| `automation` | Gatilho aprovado ([Automação](../specs/automation/design.md)) | Limitada ao escopo aprovado | Ninguém presente na hora |
| `agent` | Delegação entre agentes | A de quem iniciou | Delegação não pode escalar privilégio |
| `autonomous` | Defesa, reação a evento de segurança | Só contenção reversível | [ADR-0016](../adr/ADR-0016-defesa-deterministica.md) |

`OPERATOR` ([Defesa §2](defense.md#2-zero-trust-aplicado)) é o usuário com a
janela aberta: é a única identidade que sai do lockdown, libera disjuntor e muda
política. Nenhuma origem além de `ui` age como `OPERATOR`.

## 3. Teto de risco por origem

| Origem | GREEN | YELLOW | RED |
|---|---|---|---|
| `ui` | executa | conforme política | diálogo na tela |
| `voice` | executa | confirmação na tela | diálogo na tela; "sim" falado não vale |
| `automation` | executa | só dentro do escopo aprovado da automação | nunca; notifica e aguarda |
| `agent` | herda | herda | herda |
| `autonomous` | só contenção reversível | — | — |

Herdar significa: o teto é o **menor** entre a origem de quem iniciou a tarefa e
o teto do próprio agente ([Catálogo §3](../agents/catalog.md#3-least-privilege-na-prática)).

## 4. Voz

- A voz é boa para pedir e ruim para autorizar. Toda confirmação de efeito vai
  para a tela; com a janela fechada, o overlay chama atenção e o usuário decide
  na tela ([Voz §6](../specs/voice/design.md#6-privacidade-e-falsos-positivos)).
- Transcrição com confiança abaixo de 0,6 pede confirmação antes de qualquer ação,
  mesmo GREEN com efeito.
- Verificação de locutor, se um dia entrar, eleva a confiança de `voice` para
  leitura de dados pessoais, nunca para autorizar YELLOW ou RED.

## 5. Onde a origem é registrada

- Em `ToolCall` e `ActionRequest` (`zordon-api`): campo obrigatório `origin`,
  mais `initiatedBy` (turno, automação ou incidente).
- No `AuditLog`: cada linha tem origem e iniciador.
- No evento `USER_COMMAND`, que já tem `source: "text" | "voice"`
  ([ZWP §6](../api/zwp-protocol.md#catálogo)).

## 6. Casos de erro

| Caso | Comportamento |
|---|---|
| Ação sem `origin` | Recusada pelo `PermissionEngine` como erro de programação |
| Agente tenta elevar a origem herdada | Recusado; registrado como violação de capacidade |
| Confirmação YELLOW pedida por voz com a janela fechada | Notificação e overlay; nada executa até a decisão na tela |

## 7. Testes

- Tabela dourada origem × risco → decisão, como a de classificação
  ([Segurança §2](model.md#2-classificação-de-risco)).
- Delegação em cadeia (automação → agente → agente) nunca produz teto maior que o
  da automação.
