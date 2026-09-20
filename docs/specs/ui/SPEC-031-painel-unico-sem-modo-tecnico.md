---
document: spec-031
module: ui
section: spec
version: 1
updatedAt: 2026-09-20
securityLevel: public
tags: [spec,desktop,javafx,navegacao,ajustes,modo-tecnico]
specId: SPEC-031
---

# SPEC-031 — Painel único, sem modo técnico

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaFxAgent` |
| **Revisores** | ArchitectureAgent |
| **Marco** | M8 |
| **Supera** | SPEC-012 CA-8 (modo técnico); SPEC-010 §3 e CA-2 na parte do trilho de 56 px |

Pedida pelo owner em 2026-09-20: "Devemos ter apenas 1 tela de painel, ou seja o
modo tecnico deve ser fixo e as configuracoes deve ser dentro dele conforme os
outros aplicativos. deve ter voz, segurança e etc tudo ja na mesma pagina." A
forma foi escolhida por ele entre três: coluna de navegação com os grupos à
esquerda e uma área de conteúdo à direita.

## 1. Objetivo

Uma navegação só. Tudo que o Zordon faz está na mesma janela, alcançável pela
coluna da esquerda, sem uma segunda tela de Ajustes e sem um modo que esconde
metade do produto.

## 2. Problema

Havia **duas** navegações: o trilho de quatro ícones (Voz, Painel, Conversa,
Ajustes) e, dentro de Ajustes, uma tira de abas (Voz, Segurança, Aplicativos,
Sistema). O mesmo assunto aparecia nos dois lugares — "Agentes" era uma aba de
Ajustes e também um destino do Painel — e o **modo técnico**, desligado por
padrão, escondia Painel, Logs, Diagnóstico e os metadados das respostas.

Para o dono da máquina, esconder não protege nada: ele é o administrador. O modo
técnico era uma defesa contra um usuário que este produto não tem.

## 3. Escopo

**A coluna de navegação** (`NavigationPane`, 144 px) substitui o trilho:
- logo, os grupos `TRABALHO`, `RECURSOS` e `OPERAÇÃO` com todos os destinos, e no
  pé o botão de status do núcleo;
- cada item tem ícone e rótulo, e o selecionado é marcado por cor e barra;
- a lista rola quando não couber.

**O modo técnico deixa de existir**
- `DesktopState.technicalMode` e `technical(Destination)` são removidos.
- Painel, Logs e Diagnóstico abrem como qualquer destino.
- Os metadados da resposta aparecem sempre.
- O item "Ligar modo técnico" some dos Ajustes, e o menu do botão de status passa
  a dizer só "Abrir diagnóstico".

**Ajustes deixa de ser uma tela.** Cada aba vai para a casa do seu assunto:

| Aba de Ajustes | Onde passa a morar |
|---|---|
| Voz (modo, microfone, motor, transcrições) | A própria tela **Voz**, abaixo do console |
| Segurança (avisos, kill switch, achados, disjuntores, quarentena) | Tela **Segurança** |
| Aplicativos › automações, agentes, MCP | Telas **Automações**, **Agentes**, **MCP** (SPEC-030) |
| Aplicativos › tarefas | Tela **Tarefas**, nova |
| Sistema › memória | Tela **Memória** (SPEC-030) |
| Sistema › modo técnico | Removido |

**Sem duplicar o que já existia.** As seções de Segurança e Tarefas são as mesmas
que estavam nos Ajustes, reusadas pelas telas novas — e não uma segunda versão.
Onde a SPEC-030 tinha escrito uma lista de achados própria, ela foi apagada em
favor da dos Ajustes, que é mais completa (tem o kill switch e a liberação
supervisionada de disjuntor).

## 4. Não escopo

- Encolher a coluna para só ícones em janela estreita. Hoje a largura é o que
  sobra: o console de voz tem mínimo de ~575 px e a janela mínima é 720
  (SPEC-010 CA-2), o que limita a coluna a ~145 px. Alargar depende de collapse.
- Reordenar os grupos ou renomear destinos.
- Busca na navegação.

## 5. Arquitetura

```text
NavigationPane (144 px)            ZordonShell.screens (um visível)
┌───────────────┐                  ┌──────────────────────────────┐
│ TRABALHO      │                  │ Voz  = console + ajustes     │
│  Painel Voz   │ ── select() ───► │ Conversa · Painel · Logs     │
│  Conversa     │                  │ Diagnóstico                  │
│ RECURSOS  …   │                  │ 10 telas de destino          │
│ OPERAÇÃO  …   │                  └──────────────────────────────┘
│ ── núcleo ──  │
└───────────────┘
```

## 6. Fluxo

O usuário quer ver o microfone.
1. Clica em "Voz" na coluna.
2. A tela mostra o console e, abaixo, Modo, Teste do microfone, Microfone e
   Motor de voz — o que antes exigia ir a Ajustes › Voz.
3. Não há passo 3: não existe mais uma segunda tela para procurar.

## 7. Interfaces

```java
// Some: DesktopState.technicalModeProperty(), DesktopState.technical(Destination),
//       Destination.RAIL, Destination.railOwner(), Destination.SETTINGS,
//       VoiceSettingsView.selectSection(String).
// Entra:
Destination.TASKS;                    // a aba "Aplicativos" tinha tarefas
NavigationPane.item(Destination);     // o item da coluna, para o teste
```

## 8. Eventos

Nenhum novo.

## 9. Dados

Nenhum. O modo técnico não era persistido.

## 10. Segurança

- Nada passa a ser permitido: a coluna mostra o que o núcleo já expunha, e toda
  ação com efeito continua no motor de permissão.
- O kill switch e a liberação de disjuntor continuam sendo só desta janela.
- Mostrar Logs e Diagnóstico sem um modo especial **não** vaza segredo: o que
  aparece ali já era redigido (SPEC-014), e o registro completo sempre esteve em
  `~/.zordon/trace`.

## 11. Permissões

Nenhuma nova.

## 12. Observabilidade

O destino atual continua sendo o que a janela reporta; não há estado escondido a
diagnosticar.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Janela na largura mínima | A coluna mantém 144 px e o console não transborda |
| Muitos destinos | A lista da coluna rola; o rodapé do núcleo fica fixo |
| Destino sem tela | Cai no Painel, como antes |

## 14. Testes

- Todos os destinos estão na coluna, visíveis, e todos abrem.
- Os metadados da resposta aparecem sem nenhum modo ligado.
- O console cabe na janela mínima com a coluna do lado.
- O que era aba de Ajustes é encontrado na tela nova: kill switch e quarentena em
  Segurança, aprovação de MCP em MCP, esquecer em Memória.

### Evidências (2026-09-20)

- `ShellLayoutTest`: `todaANavegacaoEstaVisivelSemModoTecnico` percorre os 15
  destinos — cada um tem item na coluna e `select` devolve verdadeiro;
  `osMetadadosDaRespostaAparecemSempre` não liga modo nenhum;
  `oConsoleCabeSemRolagemEAColunaMantemALargura` passa em 960×720 e 720×560.
- `PermissionUiTest`: os três testes que montavam a tela de Ajustes agora montam
  `SecurityView`, `McpView` e `MemoryView`, e encontram lá `#lockdown-toggle`,
  `#mcp-approve-docker` e `#memory-forget-f_1`.
- 109 testes do `zordon-desktop` verdes.

## 15. Critérios de aceite

- `CA-1` Dada a janela, então há uma navegação só: a coluna da esquerda, sem
  segunda tela de Ajustes e sem tira de abas.
- `CA-2` Dado qualquer destino, então ele está visível na coluna e abre, sem
  depender de modo algum.
- `CA-3` Dada uma resposta do modelo, então os metadados dela aparecem sempre.
- `CA-4` Dado o conteúdo que era de cada aba de Ajustes, então ele está na tela do
  seu assunto, uma vez só.
- `CA-5` Dada a janela mínima de 720 px, então o console de voz cabe ao lado da
  coluna sem rolagem horizontal.

## 16. Impacto em outros módulos

- `zordon-desktop`: `NavigationPane` reescrito, `Destination` sem `SETTINGS`/`RAIL`
  e com `TASKS`, `DesktopState` sem modo técnico, `VoiceSettingsView` reduzido ao
  bloco de Voz, `TasksView` nova, `SecurityView` reusando as seções dos Ajustes.
- Nenhum impacto no núcleo.

## 17. Dependências

- [SPEC-010](SPEC-010-shell-compacto-centrado-na-voz.md) ·
  [SPEC-012](../voice/SPEC-012-voice-first-narracao-e-estados.md) ·
  [SPEC-030](SPEC-030-telas-proprias-dos-destinos.md) ·
  [Design system](design-system.md)
