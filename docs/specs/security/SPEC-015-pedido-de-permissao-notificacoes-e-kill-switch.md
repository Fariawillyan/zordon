---
document: spec-015
module: security
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,seguranca,permissao,notificacao,lockdown,kill-switch,m3]
specId: SPEC-015
---

# SPEC-015 — Pedido de permissão na tela, notificações e kill switch

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `SecurityAgent` |
| **Revisores** | ArchitectureAgent, JavaFxAgent |
| **Marco** | M3 (itens 4 a 6 da [ordem obrigatória](../../security/model.md#10-ordem-de-implementação)) |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

Fechar o que falta antes da primeira ação com efeito:
- o usuário decide na tela, lendo os alvos concretos;
- toda comunicação iniciada pelo Zordon passa por um lugar só e não se perde;
- existe um botão que põe o Zordon em "só leitura" na hora.

## 2. Problema

A [SPEC-014](SPEC-014-auditoria-validador-e-motor-de-permissao.md) decide
`AskUser`, mas ainda não há quem pergunte, então tudo que pede confirmação é
negado. Não há por onde o Zordon avise nada que o usuário precise saber. E o
kill switch, que [Segurança §8](../../security/model.md#8-limites-e-desligamento-de-emergência)
exige antes do primeiro comando perigoso, não existe.

## 3. Escopo

**Pedido de permissão (`ui.requestPermission`)**

- O núcleo pergunta ao desktop mais recente que declarou a capacidade
  `ui.permission-prompt`. Sem desktop, nega (SPEC-014 CA-7).
- **Diálogo**
  - Mostra o resumo gerado pelo núcleo, o risco, a origem ("pedido por voz"),
    até 50 alvos concretos (com o total) e a contagem regressiva de 60 s.
  - "Negar" é o botão padrão: Enter nega.
  - **RED:** "Autorizar" só habilita depois de marcar "Conferi os alvos".
    Não há "nesta sessão".
  - **YELLOW pela janela:** oferece "Autorizar nesta sessão".
  - Fechar ou esgotar o tempo nega.
- A janela vem para a frente quando chega um pedido, inclusive se estiver
  minimizada.

**`NotificationCenter`**

- Componente único por onde passa toda comunicação iniciada pelo Zordon
  ([Comunicação §2](../../security/communication.md#2-zordonnotificationcenter)).
- **Mensagem obrigatória:** uma `ZordonMessage` com os oito campos centrais.
  Vazio é recusado na construção.
- **Fila durável** em SQLite (`~/.zordon/state/notifications.db`): a mensagem é
  gravada antes de sair.
- **Entrega**
  - Evento `SECURITY_NOTIFICATION` (tópico `security`, que não se desassina).
  - Na reconexão, `notify.pending` devolve o que falta confirmar.
- **Confirmação:** `notify.acknowledge`. CRITICAL fica pendente até ser
  confirmada; INFO e WARNING expiram em 24 h.
- **Canais do M3**

  | Severidade | Canal |
  |---|---|
  | WARNING e acima | Banner na janela |
  | CRITICAL | Abre a janela, exige confirmação de leitura e é narrada (só o resumo) |

  Notificação nativa do Windows e overlay entram com o overlay (M2 pendente)
  e com o M7.

**Kill switch (Defense Lockdown manual)**

- **Pausar:** `security.lockdown` põe o Zordon em só leitura. Nada acima de
  GREEN executa (SPEC-014 CA-8), e a voz continua respondendo perguntas.
- **Retomar:** `security.resume` só é aceito vindo do desktop, por ação na
  tela. Voz, host e automação não retomam.
- **Persistência:** o estado fica em `~/.zordon/state/lockdown.json`.
  Reiniciar o núcleo não sai do lockdown.
- **Onde aparece**
  - Eventos `LOCKDOWN_ENTERED` e `LOCKDOWN_EXITED`.
  - Na pílula: "Zordon pausado — só leitura".
  - Botões "Pausar Zordon" e "Retomar" nos Ajustes e no menu do ícone da bandeja
    (quando o Windows oferece bandeja).

## 4. Não escopo

- Detectores, disjuntor automático e lockdown automático: M7.
- Notificação nativa do Windows pelo host, overlay e anti-fadiga (correlação,
  resumo diário): M7, sobre esta mesma fila.
- Tela de Segurança completa: M7. Aqui só o histórico mínimo em Ajustes.

## 5. Arquitetura

```text
PermissionEngine ──AskUser──► DesktopApprover ──ui.requestPermission──► desktop (diálogo)
                                   ▲                                          │
                                   └────────── {approval} ◄───────────────────┘
qualquer módulo ──► NotificationCenter ──► notifications.db ──► SECURITY_NOTIFICATION
                                                                 notify.pending / acknowledge
desktop / bandeja ──► security.lockdown / resume ──► LockdownService ──► PolicyContext.lockdown
```

- `DesktopApprover` implementa o `Approver` da SPEC-014; o motor não sabe que
  existe ZWP.
- O `NotificationCenter` fica em `zordon-core` (`zordon.core.notify`) até crescer
  ([Componentes §3](../../architecture/components.md#3-responsabilidades-e-limites)).

## 6. Fluxo

1. Ação YELLOW ou RED → `AskUser` → `ui.requestPermission` ao desktop.
2. O desktop traz a janela para a frente e mostra o diálogo; o usuário decide ou
   o tempo acaba.
3. A resposta volta como `once`, `session` ou `deny`; a SPEC-014 converte em
   decisão e audita.
4. Um aviso → `NotificationCenter.publish` → grava → evento → banner; o usuário
   confirma → `notify.acknowledge`.
5. "Pausar Zordon" → lockdown gravado → evento → pílula. "Retomar" → só pela tela.

## 7. Interfaces

| Método ZWP | Sentido | Parâmetros → resultado |
|---|---|---|
| `ui.requestPermission` | núcleo → desktop | `{requestId, tool, summary, risk, origin, targets[], targetCount, perAction, ttlMs}` → `{approval: once\|session\|deny}` |
| `notify.pending` | desktop → núcleo | `{}` → `{messages[]}` |
| `notify.acknowledge` | desktop → núcleo | `{messageId}` → `{acknowledged}` |
| `security.lockdown` | cliente → núcleo | `{reason}` → `{active, since}` |
| `security.resume` | desktop → núcleo | `{}` → `{active: false}`; recusado se não vier de um desktop |
| `security.status` | cliente → núcleo | `{}` → `{lockdown, audit, pendingNotifications}` |

## 8. Eventos

- `SECURITY_NOTIFICATION` (`security`): a mensagem inteira, com os oito campos.
- `LOCKDOWN_ENTERED` (`security`): `{reason, trigger: "user", auto: false}`.
- `LOCKDOWN_EXITED` (`security`): `{by: "user"}`.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Fila de notificações | `~/.zordon/state/notifications.db` | CRITICAL até confirmada; demais 24 h pendentes; histórico mantido (poda no M6) |
| Lockdown | `~/.zordon/state/lockdown.json` | Até o usuário retomar |

## 10. Segurança

- O resumo e os alvos vêm do núcleo, nunca do modelo (SPEC-014 §10).
- O diálogo não aceita "sim" falado: RED por voz aparece na tela e espera o
  clique ([Identidade §4](../../security/identity.md#4-voz)).
- Retomar do lockdown exige a tela: uma injeção que convença o modelo a
  "retomar" não tem por onde.

## 11. Permissões

Nenhuma nova. `security.resume` é recusado a quem não é desktop.

## 12. Observabilidade

- Log INFO de cada pedido e resposta, sem argumentos.
- `security.status` e `system.diagnostics` mostram lockdown, pendências e a
  auditoria.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Nenhum desktop conectado | O pedido é negado e auditado com `decided_by=timeout` |
| Desktop cai com o diálogo aberto | O pedido é negado |
| Banco de notificações indisponível | Nenhuma ação autônoma; o núcleo avisa pelo log e pelo `SYSTEM_ALERT` |
| Mensagem sem um dos oito campos | Recusada na construção (erro de programação) |

## 14. Testes

- `DesktopApprover` com desktop falso: pedido, resposta, sem desktop, queda e
  tempo esgotado.
- `NotificationCenter` com SQLite temporário: persistir antes de sair,
  pendentes na reconexão, confirmação, expiração por severidade e campos
  obrigatórios.
- `LockdownService`: persistência entre reinícios e retomada só pelo desktop.
- Desktop: regras do diálogo em código puro (padrão nega, RED exige conferência,
  sessão só para YELLOW pela janela) e diálogo montado sob display.

## 15. Critérios de aceite

- `CA-1` Dado `AskUser`, então o desktop mais recente com `ui.permission-prompt` recebe
  o pedido com resumo, risco, origem e alvos. Sem desktop, com queda ou em 60 s
  sem resposta, é negado.
- `CA-2` Dado o diálogo, então Enter e fechar negam. RED só autoriza depois de
  "Conferi os alvos" e nunca oferece sessão. YELLOW pela janela oferece sessão.
- `CA-3` Dada uma mensagem, então ela é gravada antes do evento e sobrevive ao
  reinício do núcleo até ser confirmada.
- `CA-4` Dada uma reconexão, então `notify.pending` devolve as não confirmadas.
  CRITICAL não expira; INFO e WARNING expiram em 24 h.
- `CA-5` Dada uma `ZordonMessage` sem algum dos oito campos, então ela não é criada.
- `CA-6` Dado "Pausar Zordon", então nada acima de GREEN executa, o estado
  sobrevive ao reinício e só o desktop retoma.
- `CA-7` Dada uma CRITICAL, então a janela vem para a frente, a mensagem exige
  confirmação de leitura e o resumo é narrado.

## 16. Impacto em outros módulos

- `zordon-api`: `Severity` e eventos novos; a capacidade `ui.permission-prompt` já era declarada pelo desktop.
- `zordon-core`: `DesktopApprover`, `zordon.core.notify`, `LockdownService`,
  métodos ZWP.
- `zordon-desktop`: diálogo, banner, pílula de lockdown, Ajustes e bandeja.
- [ZWP](../../api/zwp-protocol.md): métodos e eventos novos.

## 17. Dependências

- [SPEC-014](SPEC-014-auditoria-validador-e-motor-de-permissao.md) ·
  [Comunicação](../../security/communication.md) ·
  [Identidade](../../security/identity.md) ·
  [SPEC-010](../ui/SPEC-010-shell-compacto-centrado-na-voz.md)
