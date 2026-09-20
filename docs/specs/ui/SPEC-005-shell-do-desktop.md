---
document: spec-005
module: ui
section: spec
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [spec,desktop,javafx,shell,navegacao,inspector,diagnostico]
specId: SPEC-005
---

# SPEC-005 — Shell do desktop

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaFxAgent` |
| **Revisores** | ArchitectureAgent |
| **Marco** | M2 (dívida de interface do M1) |
| **Supera** | — |

Aprovada em 2026-09-18 como primeira entrega do M2 ([Roadmap](../../roadmap.md#m2--voz)).

## 1. Objetivo

A janela do Zordon ter a estrutura do [layout](desktop-layout.md) — navegação,
trabalho, contexto e comando sempre acessível —, com as telas do M1 dentro dela.

## 2. Problema

O M1 entregou Chat e Logs em abas, fora do layout especificado. Cada tela nova
(Voz neste marco, Permissão no M3) precisaria de um lugar para morar e não teria.
A [prévia](preview/index.html) mostra a estrutura; o cliente JavaFX não a segue.

## 3. Escopo

- Cabeçalho (56), navegação (224 / trilho 72), centro elástico, inspector (304),
  barra de estado (28), com as medidas de [Layout §2](desktop-layout.md#2-composição-e-medidas).
- Adaptação à largura nas quatro faixas de [Layout §2](desktop-layout.md#adaptação-à-janela).
- Navegação com todos os destinos de [Layout §3](desktop-layout.md#3-navegação-e-arquitetura-de-informação);
  os de marcos futuros aparecem **indisponíveis, com o marco**, e não abrem tela simulada.
- Telas: **Início**, **Chat**, **Logs** e **Diagnóstico** mínimo.
- Conversa **virtualizada**, com aviso de "Novas mensagens" quando o usuário rolou
  para cima durante o streaming.
- Composer global com destino sempre visível.
- Inspector com execução atual e consumo da sessão.
- Estados de [Layout §7](desktop-layout.md#7-estados-e-recuperação) que já têm
  contrato: inicializando, núcleo offline, sem provider.
- Método ZWP `system.diagnostics`, com o que a tela de Diagnóstico precisa.
- Fontes Inter e JetBrains Mono empacotadas, com licença, sem download em runtime.

## 4. Não escopo

- **Tela de Voz, overlay e estado real de captura.** Próximas entregas do M2; o
  cabeçalho mostra "Voz indisponível" até o host existir.
- **Pausar ações e Alertas.** Dependem de Defense Lockdown e do `NotificationCenter`
  (M3). Botão que não tem o que acionar não aparece — prometer pausa que não
  acontece é pior do que não oferecer.
- **Cartões de Segurança, Sistema e MCP no inspector.** Sem dados reais antes do
  M3/M4; a prévia os mostra com dados ilustrativos, o produto não.
- **Anexos e seleção explícita de agente no composer.** Dependem de extensão de
  `chat.send` registrada em [Revisão de design](design-review.md).
- **Resultados tipados de ferramenta** (tabela de containers). Não há ferramenta
  antes do M3.
- **Leitor de tela e escala de DPI completos.** Nomes acessíveis e foco visível
  entram aqui; a validação com Narrator e 200% fica para a revisão do marco.

## 5. Arquitetura

Tudo em `zordon-desktop`, que continua sem conhecer o núcleo — só o protocolo.

```text
ZordonDesktop (composition root da UI)
   │ CoreConnection ──► eventos e respostas ZWP
   ▼
DesktopState  (modelo observável, sem nós JavaFX — testável sem tela)
   │
   ▼
ZordonShell ── Header · Navigation · centro (Home | Chat | Logs | Diagnostics
               | Unavailable) · Inspector · Composer · StatusBar
```

A regra de [UI §1](design.md#1-papel) vale para cada tela: nenhuma decide nada.
Decisões pequenas de apresentação — em que faixa de largura estamos, para onde o
composer envia, se um destino está disponível — ficam em classes puras, testadas
sem JavaFX ([Padrões §9](../../process/code-standards.md#9-testabilidade)).

No núcleo, um único acréscimo: `SystemMethods` com `system.diagnostics`.

## 6. Fluxo

```text
abrir a janela
  estado "Inicializando" → conecta → session.hello
  system.diagnostics  → versão, providers, papéis
  chat.history        → última conversa (Início e Chat)
  navega para Início  (Chat, se já houver conversa aberta nesta execução)

digitar no composer
  em Chat          → envia para a sessão visível
  em outra tela    → chat.newSession, envia, abre Chat
  destino sempre escrito acima do campo antes do envio

núcleo cai
  banner "Núcleo offline" com última sincronização e tentativas
  composer bloqueado com o motivo; rascunho preservado
  volta → resumed:false → recarrega histórico e diagnóstico
```

## 7. Interfaces

Novo método ZWP, cliente → núcleo:

| Método | Params | Retorno |
|---|---|---|
| `system.diagnostics` | `{}` | `{core{version,startId,startedAt,uptimeSeconds}, events{lastSeq}, clients, turns{active}, providers{<id>:<estado>}, roles{<papel>:{provider,model,ready,reason?}}}` |

`providers` e `roles` vêm do `ProviderRegistry`: nomes de variável aparecem
("defina OPENAI_API_KEY"), **valores nunca**.

Em `zordon-desktop` (pacote `zordon.desktop.shell`):

```java
enum  ShellMode        // FULL, CONTEXT_DRAWER, RAIL, COMPACT — forWidth(double)
enum  Destination      // grupo, rótulo, ícone, marco, disponível no M2?
final class DesktopState     // conexão, capacidades, diagnóstico, turno, consumo, rascunho
record SessionUsage          // tokens e custo acumulados, com "estimado" contagioso
final class ComposerTarget   // para onde o composer envia, e o rótulo que diz isso
final class ZordonShell      // monta as regiões; não decide nada
```

## 8. Eventos

Nenhum evento novo. O desktop assina `chat` e `system` e passa a usar, de
`AI_RESPONSE` final, os campos `provider`, `model`, `usage`, `costUsd`,
`latencyMs` e `fallbackFrom` ([SPEC-004 §8](../core/SPEC-004-providers-configuraveis.md#8-eventos)).

## 9. Dados

Nada persistido pelo desktop — [Layout §8](desktop-layout.md#8-segurança-notificações-e-privacidade):
offline, só o histórico já carregado em memória. Fontes em
`zordon-desktop/src/main/resources/zordon/desktop/fonts/`, com as licenças ao
lado e o SHA-256 registrado em `CHECKSUMS`:

| Fonte | Origem | Licença |
|---|---|---|
| Inter 4.1 (Regular, Medium, SemiBold, Bold) | github.com/rsms/inter, release v4.1 | SIL Open Font License 1.1 |
| JetBrains Mono 2.304 (Regular) | github.com/JetBrains/JetBrainsMono, release v2.304 | SIL Open Font License 1.1 |

A OFL permite empacotar com software de qualquer licença, inclusive Apache 2.0;
proíbe vender a fonte sozinha, o que não se aplica.

## 10. Segurança

- **Que `Effect` produz?** Nenhum. O shell lê estado e envia mensagens de chat,
  como o M1 já fazia.
- **Risco, e varia?** Não há ação classificável. Nenhum destino, atalho ou
  cartão dispara ferramenta; a UI continua sem `tool.invoke` ([Layout §4](desktop-layout.md#4-início-e-chat)).
- **Superfície nova para conteúdo não confiável?** Não: o texto do modelo segue
  exibido como texto.
- **Toca segredo?** `system.diagnostics` passa perto: o estado dos providers
  inclui motivos como "defina OPENAI_API_KEY". Nome de variável sim; valor, nunca.
  Um teste garante que nem chave nem token de sessão aparecem na resposta.
- **Ação autônoma?** Nenhuma.

**Honestidade de estado** também é segurança aqui: nenhum indicador verde para o
que não foi verificado, nenhum botão para o que não existe. "Sem incidentes" não
aparece enquanto não houver detector nenhum para dizer isso.

## 11. Permissões

Não se aplica: o shell não executa ação.

## 12. Observabilidade

Diagnóstico mínimo mostra: versão e `startId` do núcleo, tempo no ar, eventos
publicados, clientes conectados, turnos ativos, estado de cada provider e de cada
papel, reconexões desta execução do desktop e latência de `session.ping`.

## 13. Casos de erro

| Falha | Comportamento | O que o usuário vê |
|---|---|---|
| Núcleo offline ao abrir | Estado "Inicializando" → "Núcleo offline" | Banner, tentativas, composer bloqueado com motivo |
| Núcleo cai no meio | Histórico carregado continua legível | Banner com a última sincronização; rascunho preservado |
| `system.diagnostics` falha | Diagnóstico mostra o erro | "Diagnóstico indisponível: <motivo>", resto da janela normal |
| Nenhum provider pronto | Início e composer dizem o motivo | Caminho: Primeiros passos §4 |
| Fonte não carrega | Cai para Segoe UI / Consolas | Nada; o log registra |
| Janela estreita | Regiões recolhem | Nada some: cancelar, voz e alertas continuam alcançáveis |
| Destino indisponível clicado | Não navega | Dica com o marco em que chega |

## 14. Testes

| Arquivo | Nível | Cobre |
|---|---|---|
| `ShellModeTest` | unidade | as quatro faixas de largura |
| `DestinationTest` | unidade | disponibilidade e marco de cada destino |
| `ComposerTargetTest` | unidade | destino do envio, rótulo, regras de Enter |
| `SessionUsageTest` | unidade | soma de consumo e estimativa contagiosa |
| `DesktopStateTest` | unidade | offline, sem provider, reconexão, rascunho |
| `ShellSnapshotTest` | interface | janela real em 1600, 1366, 1024 e 800 de largura; conversa com 2.000 mensagens |
| `SystemMethodsTest` | integração | `system.diagnostics` por ZWP, sem segredo |

`ShellSnapshotTest` precisa de tela: roda onde houver `DISPLAY` (WSLg, ou
`xvfb-run` no CI) e grava capturas em `build/ui-snapshots/` para revisão visual.

## 15. Critérios de aceite

> **Substituídos em parte pela [SPEC-010](SPEC-010-shell-compacto-centrado-na-voz.md)**
> (2026-09-18): a composição do shell (cabeçalho, navegação de 224/72 px,
> inspector, barra de estado e composer global) deu lugar ao trilho compacto.
> O `CA-1` saiu (o `ShellMode` foi removido a pedido do owner). `CA-3` e `CA-10`
> descrevem regiões que não existem mais, mas as regras puras que eles testam
> (estado do núcleo e consumo da sessão no `DesktopState`) continuam valendo. Os demais critérios
> valem: `CA-2` no Painel, `CA-4` a `CA-9` na Conversa, `CA-11` a `CA-13` como
> antes.

- ~~CA-1~~ Substituído pela [SPEC-010](SPEC-010-shell-compacto-centrado-na-voz.md): o shell não tem mais faixas de largura; o trilho tem 56 px em qualquer janela (SPEC-010 CA-8).
- `CA-2` Dado um destino de marco futuro, então ele aparece com o marco,
  indisponível, e selecioná-lo não abre tela simulada.
- `CA-3` Dado o estado da conexão, então o cabeçalho e a barra de estado dizem
  "Núcleo conectado", "Conectando…" ou "Núcleo offline", e a voz aparece como
  indisponível enquanto não houver host.
- `CA-4` Dado o núcleo offline, então o banner mostra a última sincronização e as
  tentativas, o composer fica bloqueado com o motivo, e o rascunho é preservado.
- `CA-5` Dado que nenhum provider atende a conversa, então Início e composer
  mostram o motivo devolvido pelo núcleo e o caminho para configurar.
- `CA-6` Dado o composer em Chat, então ele envia para a sessão visível; em outra
  tela, então cria uma conversa, envia e abre o Chat; o destino está escrito antes
  do envio.
- `CA-7` Dado o composer, então Enter envia, Shift+Enter quebra linha, campo vazio
  não envia e Enter durante composição de IME não envia.
- `CA-8` Dada uma conversa com 2.000 mensagens, então só as visíveis viram nós na
  tela.
- `CA-9` Dado que o usuário rolou para cima durante um streaming, então a rolagem
  não é puxada para o fim e aparece "Novas mensagens".
- `CA-10` Dado um turno concluído, então o inspector mostra provider, modelo,
  tokens, custo e duração, e o consumo da sessão soma os turnos, marcado como
  estimado se qualquer parcela for estimada.
- `CA-11` Dado o núcleo no ar, então `system.diagnostics` devolve versão,
  `startId`, tempo no ar, estado dos providers e dos papéis.
- `CA-12` Dada uma chave configurada, então nem ela nem o token da sessão aparecem
  na resposta de `system.diagnostics`.
- `CA-13` Dadas as fontes empacotadas, então elas carregam sem rede e o SHA-256 de
  cada uma bate com `CHECKSUMS`.

## 16. Impacto em outros módulos

- `zordon-core`: `SystemMethods`; `ProviderRegistry` ganha a descrição dos papéis.
- `zordon-desktop`: reestruturado em `shell`, telas e estado; `ChatView` vira
  lista virtualizada.
- CI: testes de interface sob `xvfb-run`.
- `NOTICE`: atribuição das fontes.
- Documentação: [ZWP §4](../../api/zwp-protocol.md#4-métodos--cliente--núcleo)
  ganha o formato de `system.diagnostics`.

## 17. Dependências

- [Layout desktop](desktop-layout.md) · [Design system](design-system.md) · [UI](design.md)
- [SPEC-003](../core/SPEC-003-chat-com-streaming.md) · [SPEC-004](../core/SPEC-004-providers-configuraveis.md)
