---
document: spec-033
module: ui
section: spec
version: 1
updatedAt: 2026-09-20
securityLevel: public
tags: [spec,desktop,javafx,voz,modo,console]
specId: SPEC-033
---

# SPEC-033 — O modo de voz onde ele é decidido

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaFxAgent` |
| **Revisores** | ArchitectureAgent |
| **Marco** | M8 |

Pedida pelo owner em 2026-09-20: "deixe agora o modo de voz dentro de ajuste para
configurar quando deixar ele desligado, 5 minutos e etc".

## 1. Objetivo

Escolher quando o Zordon escuta sem sair da tela onde se percebe que é preciso
mudar — o painel de **Ajustes** do próprio console.

## 2. Problema

A [SPEC-032](SPEC-032-tela-do-zordon.md) tirou os ajustes de voz da tela do
Zordon e os pôs num destino próprio. Ficou certo para o console, mas errado para
a decisão mais frequente: quem está olhando o console e quer parar de escutar
(ou abrir 5 minutos de conversa) tinha de trocar de tela.

O botão "Ajustes" do console já existia e abria um painel — só com efeitos
sonoros, que é o ajuste **menos** urgente.

## 3. Escopo

**`VoiceModePicker`**, um componente só, com os quatro modos e uma linha dizendo
o que cada um faz:

| Modo | O que a linha explica |
|---|---|
| Desligada | Microfone desligado no Windows. Nada é capturado. |
| Aguardar "Zordon" | Sempre atento: diga "Zordon" e ele atende. |
| Por atalho | Grava só enquanto o atalho global estiver pressionado. |
| Conversa aberta (5 min) | Conversa contínua por 5 minutos, sem repetir "Zordon". |

- Entra **no topo** do painel de Ajustes do console, acima dos efeitos sonoros.
- O mesmo componente atende o destino **Ajustes** — uma implementação, dois
  lugares. O que havia lá foi substituído por ele.
- O console não conhece o `DesktopState`: a tela injeta o seletor
  (`VoiceEffectsPane.modeControl`).
- Clicar no modo já selecionado não o desmarca: quem manda é o núcleo.

## 4. Não escopo

- Persistir o modo `open`, que é temporário por definição (SPEC-006 §5).
- Mudar o que cada modo faz.

## 5. Arquitetura

```text
VoiceView ──cria──► VoiceModePicker ──actions.setVoiceMode──► voice.setMode (ZWP)
    │                     ▲
    └──injeta──► VoiceEffectsPane.modeControl
VoiceSettingsView ──cria──┘   (o mesmo componente, sem cópia)
```

## 6. Fluxo

1. O usuário está na tela do Zordon e clica em "Ajustes", no console.
2. O painel abre com **Modo** no topo.
3. Ele escolhe "Conversa aberta (5 min)"; a linha abaixo confirma o que isso faz.
4. `voice.setMode` vai ao núcleo, e o estado volta pelo `VOICE_STATE`.

## 7. Interfaces

```java
final class VoiceModePicker extends VBox {
    VoiceModePicker(DesktopState state, ShellActions actions, boolean compact);
    static String explain(String mode);
}
// VoiceEffectsPane
void modeControl(Node control);
```

## 8. Eventos

Nenhum novo: `voice.setMode` e `VOICE_STATE` já existiam.

## 9. Dados

Nenhum.

## 10. Segurança

- Desligar continua desligando a captura **no host**, não filtrando no núcleo
  (design de voz §3): o LED de microfone do Windows diz a verdade.
- O seletor não decide nada sozinho: ele pede ao núcleo e mostra o que voltou.

## 11. Permissões

Nenhuma nova.

## 12. Observabilidade

O modo pedido e o em vigor continuam no `voice.status` e no log do núcleo.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Núcleo sem responder | Os botões ficam desabilitados e a linha diz que se espera o núcleo |
| Modo recusado | O estado volta do núcleo e a seleção se corrige sozinha |

## 14. Testes

- O painel de Ajustes do console traz os quatro modos, e clicar num deles pede o
  modo ao núcleo.
- O destino Ajustes usa o mesmo componente.

### Evidências (2026-09-20)

- `VoiceModeTest` (3 testes): os quatro modos existem com explicação própria — a
  de `open` diz "5 minutos" e a de `off` diz "Nada é capturado"; clicar pede o modo
  ao núcleo e a marca **não** se move até o `VOICE_STATE` confirmar; sem resposta
  do núcleo os botões ficam desabilitados.
- Esse teste pegou uma regressão na escrita desta SPEC: a primeira versão marcava
  o botão clicado na hora, o que mostraria um modo que o núcleo podia recusar. A
  seleção voltou a ser a do núcleo.
- `VoiceTalkTest` e `ShellLayoutTest` seguem verdes com o seletor único.
- A duplicação foi removida: `VoiceSettingsView.MODES` e o laço que montava os
  botões saíram em favor do componente.

## 15. Critérios de aceite

- `CA-1` Dado o painel de Ajustes do console, então ele mostra os quatro modos no
  topo, cada um com uma linha explicando o que faz.
- `CA-2` Dado um clique num modo, então o núcleo recebe `voice.setMode` com ele, e
  clicar no já selecionado não o desmarca.
- `CA-3` Dado o destino Ajustes, então ele usa o mesmo componente, sem uma segunda
  lista de modos.

## 16. Impacto em outros módulos

- `zordon-desktop`: `VoiceModePicker` novo, `VoiceEffectsPane.modeControl`,
  `VoiceSettingsView` sem o seu próprio seletor.
- Nenhum impacto no núcleo.

## 17. Dependências

- [SPEC-032](SPEC-032-tela-do-zordon.md) ·
  [SPEC-006](../voice/SPEC-006-tela-e-estado-da-voz.md) ·
  [design da voz](../voice/design.md)
