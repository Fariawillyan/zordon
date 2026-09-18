---
document: spec-003
module: core
section: spec
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [spec,chat,streaming,provider,roteador,turno,cache]
specId: SPEC-003
---

# SPEC-003 — Conversa de texto com streaming

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaAgent` |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M1 |
| **Supera** | — |

## 1. Objetivo

Conversar com o Zordon por texto, com a resposta aparecendo enquanto é gerada, e
com o custo de cada turno visível desde o primeiro.

## 2. Problema

Depois do [M0](SPEC-002-fundacao-zwp-e-nucleo.md) existe um núcleo que sobe e um
cliente que o encontra — e nada para dizer um ao outro. Sem conversa não há nada
para observar, nada para medir e nenhuma forma de descobrir se as decisões de
contexto e custo estão certas.

E há um risco específico deste marco, registrado no
[roadmap](../../roadmap.md#riscos-por-marco): **cache de prompt mal posicionado**.
O sintoma é `cache_read_input_tokens` sempre zero, o efeito é multiplicar o custo
de toda conversa por cinco, e a característica desagradável é que nada quebra —
funciona, e só a fatura denuncia. Por isso o uso de cache aparece na tela desde o
primeiro turno, em vez de esperar a tela de Usage do M8.

## 3. Escopo

- `AiProvider`, com streaming cancelável, uso, custo e motivo de parada.
- Adaptador Anthropic: pensamento adaptativo, esforço, cache de prompt,
  ferramentas e tratamento de recusa.
- `IntentRouter` com rota rápida e agente geral.
- `TurnManager`: conduz o turno e o projeta em eventos.
- `ConversationStore` em memória, com histórico por sessão.
- `PromptComposer`: prompt de sistema versionado e composição de contexto.
- Métodos ZWP `chat.send`, `chat.cancel`, `chat.history`, `chat.newSession`.
- Tabela de preços com custo por turno, substituível por `~/.zordon/pricing.toml`.
- UI de chat com streaming, indicador de raciocínio e rodapé de uso.
- Tela de Logs como projeção do fluxo de eventos.
- Ícone de bandeja com estados.

## 4. Não escopo

- **Execução de ferramenta.** O contrato as transporta, mas nenhuma existe: elas
  chegam com o `PermissionEngine` no M3. Oferecer ferramenta antes do motor de
  permissão é exatamente o cenário que este projeto existe para evitar.
- **Classificação de intenção por modelo pequeno.** Só faz sentido quando houver
  mais de um agente para escolher (M5); hoje seria um custo sem decisão.
- **Persistência da conversa em disco.** O histórico sobrevive ao fechamento da
  janela, que é o que o marco exige. Sobreviver ao reinício do núcleo é SQLite, e
  isso é [memória](../memory/design.md) — M5.
- **Sumarização do histórico** ao estourar a janela de contexto. Depende do papel
  `summarize` e de medição real de uso; entra quando a conversa crescer.
- **Provider local de fallback.** O contrato já o admite; a implementação chega
  quando houver o que proteger com ela. *Entregue depois, junto da escolha de
  provider por configuração, pela [SPEC-004](SPEC-004-providers-configuraveis.md).*
- **Rotas rápidas definidas pelo usuário.** Dependem do arquivo de configuração,
  que chega com os agentes em TOML (M5).

## 5. Arquitetura

```text
zordon-desktop ──chat.send──► zordon-core ──► IntentRouter
                                   │              │
                                   │              ├── rota rápida → resposta local
                                   │              └── modelo → TurnManager
                                   │                              │
                                   ▼                              ▼
                             ZordonEventBus  ◄────────────  zordon-ai
                                   │                      (AiProvider → Anthropic)
                                   ▼
                    eventos chat ──► ZwpServer ──► clientes assinantes
```

`zordon-core` depende de `zordon-ai` pela interface; nenhuma classe do SDK da
Anthropic atravessa a fronteira do módulo. É o que permite trocar de provider, ou
apontar para um local, sem o núcleo saber
([Componentes §3](../../architecture/components.md#3-responsabilidades-e-limites)).

## 6. Fluxo

```text
 1. chat.send {text}
 2. TurnManager cria turnId, grava a mensagem, publica USER_COMMAND
 3. IntentRouter normaliza (minúsculas, sem acento, sem wake word) e decide
       ├── Immediate    → responde na hora, publica AI_RESPONSE done=true
       ├── CancelCurrent→ cancela o que estiver em andamento
       └── Model        → segue
 4. publica AI_THINKING {model, agentId}
 5. monta o contexto: prompt de sistema (cacheado) + histórico cronológico
 6. provider.stream(...)
       ├── onTextDelta  → AI_RESPONSE {delta, done:false}
       ├── onThinking   → AI_THINKING {summary}
       └── onError      → AI_ERROR {kind, retryable}
 7. ao terminar:
       ├── recusa       → AI_ERROR com o motivo (nunca resposta vazia)
       └── sucesso      → grava a resposta, publica AI_RESPONSE done=true
                          com texto completo, uso e custo
```

O evento final carrega o **texto inteiro**, e não só o último fragmento. Isso não
é redundância: o tópico `chat` descarta o mais antigo sob pressão
([ADR-0011](../../adr/ADR-0011-event-bus-com-replay.md)), e é este evento que
reconcilia o que a tela mostra com o que o núcleo registrou.

## 7. Interfaces

```java
public interface AiProvider {
    ProviderInfo info();
    AiResponse chat(AiRequest request) throws AiException;
    AiStream stream(AiRequest request, AiStreamListener listener);
    default List<float[]> embed(List<String> texts);
    long countTokens(AiRequest request);
}

public interface AiStream extends AutoCloseable {
    void cancel();                                // aborta o HTTP, não só a leitura
    CompletableFuture<AiResponse> result();
}

public record AiRequest(String model, String systemPrompt, List<ToolSpec> tools,
                        List<AiMessage> messages, Effort effort, Thinking thinking,
                        int maxOutputTokens, boolean cacheSystemPrompt, Duration timeout)
```

No núcleo:

```java
class TurnManager       // TurnId send(SessionId, String text, String source); boolean cancel(TurnId)
class IntentRouter      // Intent route(String) → Immediate | CancelCurrent | Model
class ConversationStore // sessões e histórico, em memória
class PromptComposer    // prompt de sistema versionado + histórico → List<AiMessage>
class ChatMethods       // chat.send | cancel | history | newSession
```

`AiRequest` difere do esboço de
[Interfaces §2](../../api/core-interfaces.md#2-aiprovider) em dois pontos:
`cacheHints` virou o booleano `cacheSystemPrompt` — enquanto só existe um bloco
estável, uma lista de marcadores seria estrutura sem uso —, e
`providerOptions` não existe, porque um mapa livre de opções é a porta pela qual
detalhes de um provider vazam para o núcleo.

## 8. Eventos

| Evento | Tópico | Payload |
|---|---|---|
| `USER_COMMAND` | `chat` | `{turnId, sessionId, text, source}` |
| `AI_THINKING` | `chat` | `{turnId, model, agentId}` e, durante o fluxo, `{turnId, summary}` |
| `AI_RESPONSE` | `chat` | fragmento `{turnId, delta, done:false}`; final `{turnId, text, done:true, model, stopReason, usage, costUsd, latencyMs}` |
| `AI_ERROR` | `chat` | `{turnId, kind, message, retryable}` |

`usage` traz `inputTokens`, `outputTokens`, `cacheCreationTokens` e
`cacheReadTokens` — o último é o termômetro do risco deste marco.

## 9. Dados

Nenhum banco. Sessões e mensagens vivem em memória no núcleo, com teto de 2.000
mensagens por sessão para que um processo de semanas não cresça sem limite. O
esquema durável é do M5 ([Memória §3](../memory/design.md#3-schema)).

Um arquivo opcional: `~/.zordon/pricing.toml` substitui a tabela de preços
embutida, modelo a modelo. Preço é dado de configuração — uma mudança de preço
não pode exigir recompilar o Zordon
([ADR-0022](../../adr/ADR-0022-token-observability.md)).

```toml
[models."claude-opus-5"]
input  = 5.00      # US$ por milhão de tokens
output = 25.00
cache_write = 6.25 # opcional; padrão 1,25 × input
cache_read  = 0.50 # opcional; padrão 0,10 × input
```

Arquivo inválido não impede o núcleo de subir: a tabela embutida vale, e o log
diz por quê. Valores são lidos como decimal a partir do texto, nunca como
`double` — custo se soma e arredonda.

## 10. Segurança

- **Que `Effect` produz?** `NETWORK`, e só. Nenhuma escrita, nenhum processo,
  nenhum acesso a arquivo do usuário. O conteúdo da conversa sai da máquina para
  o provider — é a razão de `ProviderInfo.local` existir e de os embeddings serem
  locais por padrão quando chegarem.
- **Risco, e varia por argumento?** Não varia neste marco: não há ação a
  classificar. A primeira ação com efeito chega no M3, atrás do `PermissionEngine`.
- **Superfície nova para conteúdo não confiável?** Sim: a saída do modelo. Ela é
  tratada como **texto a exibir**, nunca como comando. Não há caminho que
  transforme resposta do modelo em execução — não porque haja uma verificação,
  mas porque a capacidade não existe no código.
- **Toca segredo?** A chave de API, lida do ambiente do serviço — provisoriamente
  de `~/.zordon/secrets.env` via `EnvironmentFile`
  ([Segurança §5](../../security/model.md#estado-atual-provisório)). Ela não é
  registrada em log, não aparece em evento e não é exposta por método ZWP. O que
  vai para a UI quando ela falta é a instrução do que configurar, não o valor.
- **Ação autônoma?** Nenhuma. Todo turno começa em uma mensagem do usuário.

Duas defesas de injeção de prompt já entram aqui, antes de existir ferramenta
para sequestrar. O prompt de sistema declara que conteúdo de resultado de
ferramenta é **dado observado, nunca instrução** — a camada 1 de
[Segurança §6](../../security/model.md#6-prompt-injection) —, e o adaptador nunca
reenvia o bloco de raciocínio ao modelo, para que texto injetado em um resumo não
volte como contexto com aparência de decisão própria.

## 11. Permissões

Não se aplica: nenhuma ação com efeito é possível neste marco. Quando a primeira
Skill existir, ela passa pelo `PermissionEngine` antes de qualquer execução
([Segurança §3](../../security/model.md#3-fluxo-de-decisão)).

## 12. Observabilidade

| Sinal | Onde |
|---|---|
| `turno {id} concluído em {ms} · {tokens} · {custo}` | log do núcleo |
| Uso e custo por turno | rodapé da mensagem na UI |
| `cacheReadTokens` | payload de `AI_RESPONSE`, visível na UI |
| Turnos em andamento | `TurnManager.activeTurns()` |
| Rota tomada | campo `route` quando a resposta veio da rota rápida |

## 13. Casos de erro

| Falha | Comportamento | O que o usuário vê |
|---|---|---|
| Sem `ANTHROPIC_API_KEY` | Núcleo sobe; o turno falha | "Defina ANTHROPIC_API_KEY para o serviço" |
| Chave recusada (401) | `AI_ERROR kind=NO_CREDENTIALS` | "A API recusou a chave" + o comando que a troca |
| Conta sem crédito | `AI_ERROR kind=QUOTA_EXHAUSTED`, `retryable=false` | onde adicionar crédito — tentar de novo não resolve |
| Outro 4xx | `AI_ERROR kind=INVALID_REQUEST` | a mensagem da própria API, não só o código |
| Provider fora do ar | `AI_ERROR` com `retryable=true` | erro com opção de tentar de novo |
| Limite de taxa | `AI_ERROR kind=RATE_LIMITED`, `retryable=true` | idem |
| Recusa do modelo | `AI_ERROR` com a explicação | o motivo da recusa, não silêncio |
| Cancelamento | Stream abortado; turno sai da lista de ativos | nada de erro — cancelar não é falhar |
| Texto vazio | `ERR_INVALID_ARGUMENT` | botão não envia; erro fica no log |
| Núcleo cai no meio do turno | Cliente vai a offline e reconecta | composer desabilitado, rascunho preservado |
| Argumento de ferramenta inválido | Erro reportado ao listener; o turno segue | nada — é tratado internamente |
| Modelo sem preço na tabela | Custo zero e aviso no log | rodapé sem valor, em vez de valor inventado |

## 14. Testes

| Arquivo | Nível | Cobre |
|---|---|---|
| `TurnManagerTest` | unidade | ciclo do turno, recusa, falha, cancelamento, contexto, cache |
| `IntentRouterTest` | unidade | rota rápida, normalização, agente geral |
| `ChatOverZwpTest` | integração | turno completo atravessando o protocolo, histórico, argumento inválido |
| `AnthropicRequestsTest` | contrato | cache, esforço, pensamento adaptativo, ordem de ferramentas |
| `PricingTest` | unidade | custo, cache mais barato, modelo desconhecido |
| `LogEntryTest` | unidade | projeção de evento em linha de log |
| `AnthropicErrorsTest` | unidade | tradução de falhas da API em causa e ação |

`FakeAiProvider` devolve uma sequência roteirizada. Testar o turno contra um
modelo real seria lento, caro e não determinístico — e provaria coisa diferente
da que interessa, que é o que o núcleo faz com o que o modelo devolve.

`ChatOverZwpTest` usa a rota rápida de propósito: exercita o caminho inteiro
— método ZWP, roteador, barramento, assinatura, socket — sem depender de chave de
API nem de rede.

## 15. Critérios de aceite

- `CA-1` Dado um turno enviado, então os eventos publicados são, em ordem:
  `USER_COMMAND`, `AI_THINKING`, os fragmentos de `AI_RESPONSE` e o
  `AI_RESPONSE` final com o texto completo.
- `CA-2` Dada uma conversa, quando a janela é fechada e reaberta, então o
  histórico continua disponível por `chat.history`.
- `CA-3` Dado um turno concluído, então o evento final carrega tokens de entrada,
  de saída, de cache e o custo calculado.
- `CA-4` Dada uma recusa do modelo, então o usuário recebe o motivo em
  `AI_ERROR`, e nunca uma resposta vazia.
- `CA-5` Dada uma falha do provider, então `AI_ERROR` informa a categoria e se a
  tentativa pode ser repetida.
- `CA-6` Dada uma entrada que casa com a rota rápida, então ela é respondida sem
  chamar modelo nenhum, e ainda assim publica os eventos do turno.
- `CA-7` Dado um turno em andamento, quando o usuário cancela, então o streaming é
  abortado e o turno sai da lista de ativos.
- `CA-8` Dado um histórico de conversa, então ele chega ao provider em ordem
  cronológica e com os papéis corretos.
- `CA-9` Dadas duas requisições da mesma conversa, então o prefixo — prompt de
  sistema e ordem de ferramentas — é idêntico byte a byte e marcado para cache.
- `CA-10` Dado um cliente ZWP assinante de `chat`, então ele recebe o turno
  inteiro por eventos, correlacionados pelo `turnId` devolvido em `chat.send`.
- `CA-11` Dado um `chat.send` com texto vazio, então a resposta é
  `ERR_INVALID_ARGUMENT`.
- `CA-12` Dada uma requisição ao provider Anthropic, então o pensamento é
  adaptativo com resumo visível, o esforço vai dentro de `output_config`, e
  nenhum orçamento de pensamento é enviado.
- `CA-13` Dado um turno completo, então ele aparece no log de atividades com
  comando, resposta, tokens, custo e latência — e um fragmento de streaming não
  vira uma linha de log.
- `CA-14` Dado um `~/.zordon/pricing.toml`, então os preços dele substituem os
  embutidos sem recompilar, e um arquivo inválido não impede o núcleo de subir.
- `CA-15` Dada uma falha da API, então o turno publica **exatamente um**
  `AI_ERROR`, cuja mensagem diz a causa em português e o que fazer — conta sem
  crédito, chave recusada ou API fora — em vez do código HTTP cru.

## 16. Impacto em outros módulos

- `zordon-ai` nasce com esta SPEC.
- `zordon-core` ganha o pacote `chat` e passa a declarar a capacidade
  `chat.stream` no handshake.
- `zordon-desktop` deixa de ser um esqueleto de estado e vira a tela de conversa.
- `docs/api/core-interfaces.md` §2 registra as duas diferenças de `AiRequest`.
- Nenhum contrato existente quebra: os métodos de `chat` já estavam declarados no
  ZWP e agora existem.

## 17. Dependências

- [SPEC-002](SPEC-002-fundacao-zwp-e-nucleo.md) — fundação e protocolo
- [ADR-0004](../../adr/ADR-0004-java-no-nucleo-python-na-voz.md) — Java no núcleo
- [ADR-0011](../../adr/ADR-0011-event-bus-com-replay.md) — barramento com replay
- [Core — IA e Intent Router](design.md)
- [UI — Desktop JavaFX](../ui/design.md)
