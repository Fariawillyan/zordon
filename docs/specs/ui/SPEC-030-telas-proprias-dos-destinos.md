---
document: spec-030
module: ui
section: spec
version: 1
updatedAt: 2026-09-20
securityLevel: public
tags: [spec,desktop,javafx,destinos,telas,navegacao]
specId: SPEC-030
---

# SPEC-030 — Telas próprias dos destinos

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaFxAgent` |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M8 |

Pedida pelo owner em 2026-09-20, depois de ver o Painel: "verifique se esses
items foram implementados" e, diante do resultado, "construa as proprias telas".

## 1. Objetivo

Cada destino do Painel abre a **sua** tela, com o que o núcleo já sabe — em vez
de dizer que o recurso "chega no M5" quando ele chegou há dias.

## 2. Problema

[Destination](../../../zordon-desktop/src/main/java/zordon/desktop/shell/Destination.java)
marca nove destinos com um marco futuro (`pendingUntil`), e
[SPEC-010 §3](SPEC-010-shell-compacto-centrado-na-voz.md#3-escopo) manda mostrar
o destino que ainda não existe, indisponível e com o marco, para o usuário saber
que ele virá. A regra era certa e virou mentira ao contrário: M3 a M8 foram
entregues, os métodos ZWP respondem, e a tela continua dizendo "Agentes chega no
M5". Quem clica recebe uma recusa por um motivo que não existe mais.

Hoje seis desses assuntos só são alcançáveis por dentro dos Ajustes (seções
"Agentes", "Servidores MCP", "Automações", "Memória", "Segurança", "Sistema"), e
três — Skills, Conhecimento e Uso — não têm porta nenhuma.

## 3. Escopo

**Nove telas novas**, uma por destino, no padrão do `DiagnosticsView`: trilha
(`crumb`), título, botão *Atualizar*, e seções com o que o núcleo devolve.

| Destino | Fonte no núcleo | O que a tela mostra |
|---|---|---|
| Agentes | `agent.list`, `agent.runs` | Perfis com teto, orçamento e origem; execuções recentes |
| MCP | `mcp.servers`, `mcp.approve` | Servidores, estado, ferramentas, disjuntor e a aprovação de superfície nova |
| Skills | `tools.list` | As ferramentas que o modelo pode pedir, com risco e efeitos |
| Automações | `automation.list` e ações | Ativas, propostas esperando aprovação, gatilho e última execução |
| Memória | `memory.facts`, `memory.forget` | Fatos com procedência e o "esquecer" — que só existe aqui |
| Conhecimento | `rag.status`, `rag.roots`, `rag.reindex` | Arquivos e pedaços indexados, raízes, índice velho, reindexar |
| Sistema | `system.metrics`, `monitor.status` | CPU, memória, disco, rede e o monitor de contêineres |
| Uso | `usage.summary` | Tokens do dia e dos sete dias, por ator e por modelo |
| Segurança | `security.status/findings/events/breakers` | Lockdown, achados abertos, disjuntores e os últimos eventos |

**Disponibilidade deixa de ser data e passa a ser fato**
- O `pendingUntil` desses nove some. O mecanismo continua, para o destino que
  realmente não existir.
- `unavailableReason()` perde o ramo morto de `SETTINGS` (inalcançável desde que
  `SETTINGS` ficou disponível).

**Carga sob demanda**
- A tela pede os dados ao abrir, e só na primeira vez; *Atualizar* repete.
- Nada é pedido na inicialização: abrir o desktop não dispara nove chamadas.

**Segurança da tela** (inalterada, e aqui reafirmada)
- Nenhuma tela executa nada por conta própria: ação com efeito continua passando
  pelo motor de permissão, e o que já era YELLOW continua pedindo confirmação.
- Nenhum valor de segredo aparece: providers e chaves são mostrados por nome e
  estado, como no Diagnóstico.

## 4. Não escopo

- Tirar as seções equivalentes dos Ajustes: elas continuam funcionando, e mexer
  no `VoiceSettingsView` agora conflitaria com trabalho em andamento do owner.
  Fica registrado como o passo seguinte, quando aquele trabalho entrar.
- Edição pela tela (criar agente, escrever automação, editar `config.toml`):
  continua sendo arquivo, e a tela mostra o caminho.
- Gráfico de série temporal em Uso e Sistema: números e listas primeiro.

## 5. Arquitetura

```text
Destination (sem pendingUntil)  ─►  DesktopState.select  ─►  ZordonShell.show
                                                               │
                        ┌──────────────────────────────────────┘
                        ▼
        AgentsView · McpView · SkillsView · AutomationsView · MemoryView
        KnowledgeView · SystemView · UsageView · SecurityView
                        │
                        ├─ leem listas observáveis do DesktopState
                        └─ pedem carga por ShellActions (ZWP), nunca direto
```

## 6. Fluxo

O usuário abre o Painel e clica em "Conhecimento".
1. `state.select(KNOWLEDGE)` passa, porque o destino está disponível.
2. `KnowledgeView` aparece e, por ser a primeira vez, chama `loadKnowledge()`.
3. `rag.status` volta com arquivos, pedaços, raízes e quantos estão velhos.
4. A tela mostra os números e, se houver índice velho, diz isso com o botão de
   reindexar ao lado.

## 7. Interfaces

```java
// ShellActions ganha o que faltava; o resto já existia.
default void loadSkills() {}
default void loadKnowledge() {}
default void reindexKnowledge() {}
default void loadUsage() {}
default void loadSystem() {}
default void loadSecurityEvents() {}
```

## 8. Eventos

Nenhum novo. As telas leem o que o núcleo já publica e pedem o resto por
requisição.

## 9. Dados

Nenhuma tabela nova: tudo vem do núcleo por ZWP, e nada é gravado pela tela.

## 10. Segurança

- A tela continua sendo o único lugar que aprova (MCP, automação, quarentena) e
  o único que esquece um fato — igual às seções que ela substitui.
- Um destino a mais não amplia superfície: são os mesmos métodos ZWP, com a
  mesma exigência de `ClientKind.DESKTOP`.

## 11. Permissões

Nenhuma nova. Leitura é GREEN; aprovar e esquecer seguem o que a SPEC de cada
assunto já define.

## 12. Observabilidade

O que cada tela mostra é o mesmo que `system.diagnostics` devolve; divergência
entre os dois é defeito.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Núcleo fora do ar | A tela abre com o que tem e diz "sem conexão com o núcleo" |
| Método recusado | A tela mostra o motivo devolvido, sem tentar de novo sozinha |
| Lista vazia | Frase em palavras ("Nenhum agente disponível."), nunca tabela vazia |
| Índice de RAG inexistente | Conhecimento diz como reindexar, em vez de mostrar zero |

## 14. Testes

- Cada um dos nove destinos abre a sua tela, e o `ZordonShell` mostra o nó certo.
- Nenhum dos nove aparece como indisponível no Painel, e clicar navega.
- A carga é pedida uma vez ao abrir, e de novo só no *Atualizar*.
- Lista vazia vira frase, não espaço em branco.

### Evidências (2026-09-20)

- `DestinationPagesTest` (13 testes):
  - CA-1: nenhum dos nove tem selo, motivo de indisponibilidade ou recusa;
  - CA-2: os nove abrem, cada um com a sua tela visível — e só ela;
  - CA-3: montar o shell não faz nenhuma chamada; abrir pede uma; voltar não
    repete; *Atualizar* repete;
  - CA-4: sem agentes, a lista diz "Nenhum agente disponível"; com um, mostra o
    teto em palavras ("verde") em vez de `green`;
  - CA-5: uma carga que lança não derruba a navegação — a tela abre assim mesmo.
- `ShellRulesTest`: o mecanismo de marco futuro continua existindo, e nenhum
  destino está nele.
- `ShellLayoutTest`: o Painel leva ao MCP em vez de recusar com "chega no M4".

## 15. Critérios de aceite

- `CA-1` Dado o Painel, então nenhum dos nove destinos implementados aparece com
  selo de marco nem com aparência de indisponível.
- `CA-2` Dado um clique em cada um dos nove, então o destino muda e a tela
  correspondente fica visível.
- `CA-3` Dada a primeira abertura de uma tela, então ela pede a carga ao núcleo
  uma vez; abrir de novo não repete, e *Atualizar* repete.
- `CA-4` Dada uma lista vazia, então a tela diz isso em palavras.
- `CA-5` Dado o núcleo sem responder, então a tela abre e mostra o motivo, sem
  travar o shell.

## 16. Impacto em outros módulos

- `zordon-desktop`: nove telas novas, `Destination` sem os marcos vencidos,
  `ShellActions` com seis cargas novas, `ZordonShell` roteando.
- Nenhum impacto no núcleo: nenhum método ZWP novo.

## 17. Dependências

- [SPEC-010](SPEC-010-shell-compacto-centrado-na-voz.md) ·
  [Design system](design-system.md) ·
  [SPEC-020](../mcp/SPEC-020-cliente-mcp.md) ·
  [SPEC-021](../memory/SPEC-021-memoria-de-longo-prazo.md) ·
  [SPEC-022](../agents/SPEC-022-agentes-como-configuracao.md) ·
  [SPEC-025](../automation/SPEC-025-automacoes.md) ·
  [SPEC-027](../defense/SPEC-027-resposta-e-disjuntor.md) ·
  [SPEC-028](../rag/SPEC-028-base-de-conhecimento.md) ·
  [SPEC-029](../process/SPEC-029-engenharia-preflight-e-uso.md)
