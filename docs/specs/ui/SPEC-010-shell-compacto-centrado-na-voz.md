---
document: spec-010
module: ui
section: spec
version: 2
updatedAt: 2026-09-20
securityLevel: public
tags: [spec,desktop,javafx,shell,voz,console,trilho,layout]
specId: SPEC-010
---

# SPEC-010 — Shell compacto centrado na voz

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaFxAgent` |
| **Revisores** | ArchitectureAgent |
| **Marco** | M2 |
| **Supera** | SPEC-005 §3 (composição) e CA-1, CA-3, CA-10; SPEC-006 CA-12 e CA-13 (onde o estado aparece); SPEC-008 CA-1 |

Pedida pelo owner em 2026-09-18, com uma imagem de referência: "seguir esse
modelo para o menu lateral, enfatizar o modelo de voz, sem caixas de texto no
rodapé e sem cabeçalhos, exatamente igual à imagem".

## 1. Objetivo

A janela do Zordon ser o console de voz: um trilho fino de ícones à esquerda e o
console ocupando o resto, sem cabeçalho, sem barra de estado e sem caixa de texto
fora da conversa.

## 2. Problema

O shell da [SPEC-005](SPEC-005-shell-do-desktop.md) seguiu o layout de painel
(cabeçalho, navegação de 224 px, inspector, composer global, barra de estado).
Com a voz como modo principal, ele mostra em toda tela controles que o usuário
não está usando e deixa o console em segundo plano. O owner quer o layout
compacto da imagem de referência.

## 3. Escopo

- **Trilho** de 56 px em qualquer largura: logo; Voz, Painel, Conversa e Ajustes;
  no pé, o indicador do núcleo. O destino atual tem a barra ciano à esquerda.
- **Voz** (tela inicial): o console em tela cheia — moldura com cantos, "EFEITOS
  SONOROS" e o formato no alto à esquerda, "Testar som" e "Ajustes" no alto à
  direita com "NÍVEL RMS" abaixo, esfera com "ZORDON / OUVINDO O FUTURO", ondas
  laterais e "VOZ QUE TRANSCENDE" embaixo.
- **Pílula de estado** no alto do console, só fora do repouso: os rótulos da
  [SPEC-006](../voice/SPEC-006-tela-e-estado-da-voz.md) ou "Núcleo offline —
  reconectando"; com a captura ligada, oferece "Desligar". Repouso é núcleo
  conectado e voz desligada.
- **Painel**: Início, Logs, Diagnóstico e os destinos futuros, indisponíveis com o
  marco.
- **Conversa**: a conversa virtualizada com o composer no pé — o único lugar onde
  há caixa de texto.
- **Ajustes**: modos de voz, microfone (host, captura, dispositivo), teste do
  microfone e motor — o que saiu da tela de Voz.
- Janela padrão de 960 × 720, mínima de 720 × 560.

### Ajustes e avisos compactos (2026-09-20)

Correção visual solicitada pelo owner a partir das capturas do desktop:
controles sem tema, painéis extensos e banner esticado até o fundo da janela.

- Ajustes organizados em **Voz**, **Segurança** (avisos, proteção e quarentena),
  **Aplicativos** (automações, tarefas, agentes e MCP) e **Sistema** (memória e
  modo técnico). Só a categoria selecionada ocupa espaço.
- Botões têm tema escuro de base, foco visível e ações de atualização no
  cabeçalho dos cartões. Texto de linhas pode quebrar; ações mantêm a largura.
- Listas de avisos e achados têm rolagem própria, com altura máxima de 230 e
  260 px. Listas vazias ocupam apenas a altura do texto.
- O banner no canto superior direito tem largura máxima de 460 px e altura
  do conteúdo, gravidade, título, resumo, contador de pendentes e **Entendi**.
  Texto extenso fica resumido no banner, com dica contendo o texto completo.
- **Ver avisos** abre Segurança com os detalhes completos e recolhe o banner
  durante essa consulta. Consultar não confirma leitura; **Entendi** confirma
  um aviso por vez. Ao sair dos Ajustes, pendências voltam ao banner.
- A ordem dos avisos, os comandos e as regras de segurança permanecem os do
  núcleo; esta correção altera apresentação e navegação.

Validação: `ShellLayoutTest` monta cenas em 720 × 560 e 960 × 720, verifica
altura do banner, textos longos, fila, leitura, navegação e altura das listas.
Capturas em `zordon-desktop/build/ui-snapshots/`: `aplicativos-{720,960}.png`,
`seguranca-{720,960}.png` e `aviso-compacto-{720,960}.png`.

### Ícone da janela e do Windows

A janela usa a marca geométrica ciano do console em 16, 32, 48, 64, 128 e
256 px, substituindo o ícone padrão do Java. A distribuição inclui `zordon.ico`
e o instalador o associa ao atalho do menu Iniciar. Os arquivos são reproduzíveis
com `java packaging/windows/GenerateIcon.java`, executado na raiz do repositório.

## 4. Não escopo

- Mudança no núcleo ou no protocolo: esta SPEC só reorganiza a interface.
- Editar `config.toml` pela tela de Ajustes: continua fora, como na SPEC-005.
- `HeaderBar`, `StatusBar` e `ShellMode` deixaram de ser usados e foram
  removidos a pedido do owner; `Inspector` continua pelo auxiliar de linhas.

## 5. Arquitetura

```text
┌────┬───────────────────────────────────────────────┐
│ ▲  │ ┌───────────────────────────────────────────┐ │
│    │ │ EFEITOS SONOROS        [▶ Testar] [⚙ Ajustes]│
│ ▌≋ │ │ 44 100 Hz / ESTÉREO     ( pílula )  NÍVEL RMS│
│ ▦  │ │                 ◯ ZORDON                    │ │
│ ▤  │ │ ≈≈≈≈≈≈≈≈≈≈≈≈   OUVINDO O FUTURO  ≈≈≈≈≈≈≈≈≈≈ │ │
│ ⚙  │ │                                             │ │
│    │ │            ── VOZ QUE TRANSCENDE ──         │ │
│ ─  │ └───────────────────────────────────────────┘ │
└────┴───────────────────────────────────────────────┘
 trilho           tela do destino (Voz na imagem)
```

`ZordonShell` compõe `NavigationPane` (agora o trilho) e a pilha de telas.
`VoiceView` é só o console e a pílula; `VoiceSettingsView` recebe modos,
microfone, teste e motor; `HomeView` vira o Painel e reúne Início, Logs,
Diagnóstico e destinos futuros; a Conversa junta `ChatView` e `ComposerBar`.

As garantias de privacidade da SPEC-006 continuam, em outro lugar: a pílula
mostra o estado sempre que ele não for o repouso, e "Desligar" aparece sempre que
o host confirmou a captura ligada.

## 6. Fluxo

1. Abrir o Zordon mostra a Voz em repouso: igual à imagem.
2. Ligar o microfone (teste ou modo) faz a pílula aparecer, com "Desligar".
3. Núcleo cai: o indicador do trilho fica vermelho e a pílula diz "Núcleo
   offline — reconectando".
4. Conversa: o composer envia para a conversa visível, ou abre uma nova.

## 7. Interfaces

Sem interface nova. `Destination` ganha o conceito de trilho: `VOICE`, `HOME`
(Painel), `CHAT` (Conversa) e `SETTINGS` (Ajustes, agora disponível); `LOGS` e
`DIAGNOSTICS` abrem pelo Painel e acendem o Painel no trilho.

## 8. Eventos

Os mesmos: `VOICE_STATE`, `VOICE_LEVEL`, eventos de conversa e sistema.

## 9. Dados

Nenhum.

## 10. Segurança

- A pílula não some com o microfone ligado, pendente ou sem confirmação; o
  repouso exige captura confirmada desligada ou sem host e modo desligado.
- "Desligar" continua a um clique de qualquer estado de captura ligada.

## 11. Permissões

Nenhuma nova.

## 12. Observabilidade

Indicador do núcleo no trilho com texto acessível e dica: estado, versão e
tentativas de reconexão.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Núcleo offline | Indicador vermelho; pílula "Núcleo offline — reconectando"; Conversa bloqueia o envio e guarda o rascunho |
| Destino futuro no Painel | Indisponível, com o marco (SPEC-005 CA-2) |
| Janela no mínimo | Console reduz proporcionalmente; nada rola na horizontal |

## 14. Testes

Regras puras (repouso, pílula, trilho) sem toolkit; telas montadas sob display,
com capturas em 960 × 720 e 720 × 560 comparadas com a imagem de referência.

### Evidências (2026-09-18)

- Capturas em `zordon-desktop/build/ui-snapshots/`: `voz-repouso-960.png`,
  `voz-repouso-720.png`, `voz-repouso-1366.png`, `ajustes-960.png`,
  `conversa-960.png`, comparadas com a imagem de referência.
- Diferença deliberada: em repouso o "NÍVEL RMS" fica apagado (na referência
  aparece meio aceso); aceso sem som seria um nível inventado.
- `verifyAll` verde; desktop reinstalado no Windows e conectado ao núcleo.
- Aprovado pelo owner na janela real em 2026-09-18.

## 15. Critérios de aceite

- `CA-1` Dada a janela, então há só o trilho (logo, Voz, Painel, Conversa, Ajustes
  e o indicador do núcleo) e a tela do destino: sem cabeçalho, sem barra de estado
  e sem caixa de texto fora da Conversa; ela abre na Voz.
- `CA-2` Dada a Voz em repouso, então o console é o único conteúdo, com os textos
  "EFEITOS SONOROS", o formato, "NÍVEL RMS", "ZORDON", "OUVINDO O FUTURO" e "VOZ
  QUE TRANSCENDE" e os botões "Testar som" e "Ajustes".
- `CA-3` Fora do repouso, então a pílula mostra o estado da voz ou do núcleo e,
  com a captura ligada, "Desligar"; nunca diz "desligado" sem confirmação.
- `CA-4` Dado o estado do núcleo, então o indicador do trilho tem uma cor por
  estado e um texto acessível com o detalhe.
- `CA-5` ~~Dado o Painel, então ele leva a Início, Logs e Diagnóstico e mostra os
  destinos futuros indisponíveis, com o marco.~~ **Superado pela
  [SPEC-032](SPEC-032-tela-do-zordon.md) em 2026-09-20:** o Painel saiu por ser
  redundante — a coluna leva a todos os destinos diretamente. A prova é
  `SPEC-032/CA-2`.
- `CA-6` Dada a Conversa, então o composer fica no pé dela e envia para a
  conversa visível.
- `CA-7` Dados os Ajustes, então eles mostram modos (pedido e em vigor), microfone,
  dispositivo, teste do microfone e motor.
- `CA-8` Dadas as larguras de 960 e 720 px, então o console cabe sem rolagem
  horizontal e o trilho mantém 56 px.

## 16. Impacto em outros módulos

- `zordon-desktop`: `ZordonShell`, `NavigationPane`, `VoiceView`,
  `VoiceEffectsPane`, `VoiceVisualizer`, `HomeView`, `Destination`, `DesktopState`,
  CSS; nova `VoiceSettingsView`.
- SPECs 005, 006 e 008: critérios substituídos marcados, com link para esta.

## 17. Dependências

- [SPEC-005](SPEC-005-shell-do-desktop.md) · [SPEC-006](../voice/SPEC-006-tela-e-estado-da-voz.md) ·
  [SPEC-008](../voice/SPEC-008-console-visual-e-efeitos-sonoros.md) · [SPEC-009](../voice/SPEC-009-frames-de-audio-e-teste-do-microfone.md)
