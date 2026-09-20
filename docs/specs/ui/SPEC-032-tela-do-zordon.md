---
document: spec-032
module: ui
section: spec
version: 1
updatedAt: 2026-09-20
securityLevel: public
tags: [spec,desktop,javafx,voz,console,navegacao]
specId: SPEC-032
---

# SPEC-032 — A tela do Zordon

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaFxAgent` |
| **Revisores** | ArchitectureAgent |
| **Marco** | M8 |
| **Supera** | SPEC-010 CA-5 (o Painel) |

Pedida pelo owner em 2026-09-20, com uma imagem de referência: "tem que ter uma
tela somente do ZORDON que eh a tela principal no trabalho. Retire o botao do
painel que esta redundante nessa parte do trabalho. (…) Devemos também enfatizar
o zordon central."

## 1. Objetivo

A Voz é **a** tela do Zordon: o console ocupa a janela, e sob ele quatro cartões
dizem se dá para falar agora. Nada de formulário competindo com o centro.

## 2. Problema

A [SPEC-031](SPEC-031-painel-unico-sem-modo-tecnico.md) empilhou os ajustes de
voz debaixo do console na mesma página. O resultado foi o contrário do
pretendido: o console virou uma faixa no topo e os formulários tomaram a tela —
exatamente o que a referência do owner não quer.

E o **Painel** ficou redundante: ele era a grade de atalhos para destinos que a
coluna da SPEC-031 já lista, e ainda ocupava a primeira posição do grupo
TRABALHO, disputando com a tela que deveria ser a principal.

## 3. Escopo

**O Painel sai.** O destino `HOME` e o `HomeView` são removidos. `TRABALHO` fica
com **Voz** e **Conversa**, nessa ordem, e a Voz é a tela inicial.

- O utilitário `section(título, corpo)`, que morava no `HomeView` e é usado por
  todas as telas, vira `Cards.section` — ele nunca foi do Painel.

**Os ajustes de voz voltam a ter destino próprio**, "Ajustes", no fim do grupo
OPERAÇÃO — como a referência mostra. É um destino entre outros, não a tela com
abas que a SPEC-031 eliminou.

**A tela do Zordon** passa a ser:

```text
┌──────────────────────────────────────────────┐
│  console (VoiceEffectsPane) — cresce e manda │
│      anel · marca · ondas · orbe de falar    │
├──────────────────────────────────────────────┤
│ Microfone │ Voz (TTS) │ Modelo │ Memória     │  ← faixa de estado
└──────────────────────────────────────────────┘
```

**A faixa de estado** (`VoiceStatusStrip`) responde, sem sair da tela, às quatro
perguntas de antes de falar:

| Cartão | Verde | Âmbar | Cinza |
|---|---|---|---|
| Microfone | ligado, com o nome do dispositivo | desligado | host não conectado |
| Voz (TTS) | motor pronto | o motivo de não estar | sem resposta |
| Modelo | provider · modelo | provider indisponível | sem núcleo |
| Memória | ativa | — | sem núcleo |

O ponto colorido e a palavra dizem a mesma coisa: cor não é o único sinal.

**A coluna** ganha a assinatura da referência (marca, "SEMPRE AO SEU LADO") e um
rodapé com versão, estado e o botão de status.

## 4. Não escopo

- Os cartões laterais "Conversa aberta" e "Ações rápidas" da referência.
- O texto "Clique para falar / Alt+Space" sob o orbe: o orbe já é clicável e tem
  rótulo acessível (SPEC-012 CA-7).
- Alargar a coluna além de 144 px — continua limitada pelo console (§13).

## 5. Arquitetura

```text
VoiceView (StackPane)
└── VBox
    ├── VoiceEffectsPane   (VGROW ALWAYS — é ele que manda na altura)
    └── VoiceStatusStrip   (4 cartões, altura própria)
```

## 6. Fluxo

O usuário abre o Zordon.
1. A janela abre na Voz, e o console ocupa tudo.
2. A faixa diz "Microfone: desligado", "Voz (TTS): pronto", o provider e "Memória:
   ativa".
3. Para mudar o modo ou testar o microfone, ele vai a **Ajustes**, na coluna.

## 7. Interfaces

```java
// Some: Destination.HOME, HomeView.
// Entra:
Destination.SETTINGS;              // rótulo "Ajustes", grupo OPERAÇÃO
final class Cards { static VBox section(String title, Node body); }
final class VoiceStatusStrip extends HBox { String value(int index); }
```

## 8. Eventos

Nenhum novo: a faixa lê `VOICE_STATE`, o estado da conexão e o diagnóstico.

## 9. Dados

Nenhum.

## 10. Segurança

- A faixa mostra nome de dispositivo e de provider — nunca chave, como o resto da
  interface.
- Tirar o Painel não esconde nada: tudo que ele listava está na coluna.

## 11. Permissões

Nenhuma nova.

## 12. Observabilidade

A faixa é a leitura rápida do que `system.diagnostics` já responde; divergir dele
é defeito.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Sem resposta de voz | Os cartões dizem "Sem resposta", em cinza |
| Núcleo offline | Modelo e Memória ficam cinzas; o console continua desenhando |
| Janela em 720 px | O console cabe ao lado da coluna de 144 px, sem rolagem |

**Por que a coluna tem 144 px:** o console não encolhe abaixo de ~576 px e a
janela mínima é 720 (SPEC-010 CA-2). Medido: 208, 180, 168 e 160 px de coluna
fazem o console transbordar em 720×560. Para alargar seria preciso encolher a
coluna a só ícones em janela estreita, que fica para depois.

## 14. Testes

- A coluna leva direto a qualquer destino, e não existe destino "Painel".
- `TRABALHO` tem exatamente Voz e Conversa.
- A faixa mostra o estado real de microfone, motor, modelo e memória.
- O console cabe na janela mínima com a coluna do lado.

### Evidências (2026-09-20)

- `ShellLayoutTest.aColunaLevaDiretoAQualquerDestinoSemPainel` (CA-2): nenhum
  destino se chama "Painel", `TRABALHO` é exatamente `[VOICE, CHAT]`, e clicar nos
  itens da coluna abre Logs e MCP.
- `oConsoleCabeSemRolagemEAColunaMantemALargura`: verde em 960×720 e 720×560.
- Captura `voz-repouso-960.png`: console centrado ocupando a janela, os quatro
  cartões embaixo, 14 destinos na coluna sem rótulo cortado.
- `OrbHoverTest` (CA-1): o orbe de falar não pinta fundo em `:hover`, `:armed`
  nem `:pressed`. O tema padrão do JavaFX dá fundo a botão nesses estados e, com
  a forma de círculo do orbe, isso cobria a marca com um disco escuro — relatado
  pelo owner em 2026-09-20. O teste foi conferido ao contrário: sem a regra de
  CSS, ele reprova em `:hover`.
- 113 testes do `zordon-desktop` verdes.

## 15. Critérios de aceite

- `CA-1` Dada a janela, então a Voz é a tela inicial e o console ocupa a área de
  conteúdo, sem formulário dividindo espaço com ele.
- `CA-2` Dado o grupo TRABALHO, então ele tem só Voz e Conversa, e não existe
  destino "Painel".
- `CA-3` Dada a tela da Voz, então a faixa mostra microfone, voz, modelo e
  memória, cada um com palavra e cor.
- `CA-4` Dados os ajustes de voz, então eles são um destino próprio, "Ajustes".

## 16. Impacto em outros módulos

- `zordon-desktop`: `HomeView` removido, `Cards` novo, `VoiceStatusStrip` novo,
  `VoiceView` em coluna, `Destination` sem `HOME` e com `SETTINGS`.
- Nenhum impacto no núcleo.

## 17. Dependências

- [SPEC-010](SPEC-010-shell-compacto-centrado-na-voz.md) ·
  [SPEC-031](SPEC-031-painel-unico-sem-modo-tecnico.md) ·
  [SPEC-012](../voice/SPEC-012-voice-first-narracao-e-estados.md) ·
  [Design system](design-system.md)
