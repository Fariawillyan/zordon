---
document: spec-004
module: core
section: spec
version: 1
updatedAt: 2026-09-18
securityLevel: internal
tags: [spec,provider,configuracao,openai,ollama,reserva,contrato]
specId: SPEC-004
---

# SPEC-004 — Providers configuráveis e adaptador compatível com OpenAI

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaAgent` |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M1 (complemento) |
| **Supera** | — |

Aprovada em 2026-09-18 como fase 1 do plano de independência de provider
([ADR-0026](../../adr/ADR-0026-provider-agnostico.md)).

## 1. Objetivo

Qualquer pessoa conversa com o Zordon usando o modelo que tem — API da Anthropic,
da OpenAI, um serviço compatível ou um modelo local — escolhido num arquivo de
configuração, sem recompilar.

## 2. Problema

Depois da [SPEC-003](SPEC-003-chat-com-streaming.md), o único provider possível
era a API da Anthropic, criado diretamente no composition root. A primeira
conversa real foi recusada por falta de crédito na conta, e não havia para onde
desviar. O Zordon será usado por pessoas com contas diferentes; amarrá-lo a um
fornecedor exclui quem não tem aquela conta.

## 3. Escopo

- `~/.zordon/config.toml` com `[ai.providers.<id>]` e `[ai.roles]`.
- Referência de segredo `env:NOME`; valor literal recusado.
- `ProviderRegistry`: cria os providers da configuração e resolve papel →
  provider, modelo e esforço.
- Adaptador **compatível com OpenAI** (Chat Completions com streaming), sem
  dependência nova: OpenAI, Ollama, LM Studio, vLLM, OpenRouter, Groq, DeepSeek e
  o endpoint compatível do Gemini.
- Esforço de raciocínio **opcional**: só é enviado quando configurado.
- Consumo **estimado e marcado** quando o provider não o informa.
- Papel `fallback`: reserva de um turno quando o provider principal falha antes
  de responder.
- Kit de testes de contrato que todo provider precisa passar.
- `set-api-key.sh` para mais de um provider, sem apagar as chaves dos outros.
- Exemplo de `config.toml` instalado junto, se o usuário ainda não tiver um.

## 4. Não escopo

- **Assinaturas (Claude, ChatGPT) via `claude` e `codex`.** Fase seguinte, com
  ADR próprio: envolve iniciar processo, isolamento e termos de uso.
- **SDK oficial da OpenAI.** O protocolo compatível é pequeno e estável; um SDK
  traria dependência para falar o que o cliente HTTP do JDK já fala.
- **Adaptador nativo do Gemini.** O endpoint compatível cobre a conversa; recursos
  exclusivos esperam um motivo concreto.
- **Tradução de nomes de ferramenta** (`skill:files.read` não é um nome aceito
  pelas APIs). Só importa quando houver ferramenta — M3.
- **Troca de provider a quente.** A configuração é lida na inicialização.
- **Descoberta automática de modelos.** O usuário escreve o nome do modelo; o
  Zordon não adivinha qual usar.

## 5. Arquitetura

```text
~/.zordon/config.toml ──► AiSettings ──► ProviderRegistry
                                              │
                          ┌───────────────────┼─────────────────────┐
                          ▼                   ▼                     ▼
                   AnthropicProvider   OpenAiCompatibleProvider   (fase 2: CLIs)
                   (SDK oficial)       (java.net.http + SSE)
                          │                   │
                          └──── AiProvider ◄──┘
                                   ▲
            zordon-core ── TurnManager pede "o provider do papel X"
```

O núcleo depende só de `AiProvider` e do registro. Regras do ArchUnit tornam isso
verificável: `zordon.core` não importa adaptador, e o SDK da Anthropic não aparece
fora de `zordon.ai.anthropic`.

## 6. Fluxo

```text
inicialização
  lê config.toml (ou usa o padrão: anthropic + ANTHROPIC_API_KEY)
  para cada provider: resolve a chave pela referência env:
      sem chave → provider indisponível, com o motivo guardado
  registra no log o estado de cada provider

turno
  registro.select(conversation) → provider, modelo, esforço
      indisponível → AI_ERROR com o motivo ("defina OPENAI_API_KEY")
  stream
      falha antes do primeiro fragmento, por indisponibilidade, limite,
      crédito ou chave?
          sim → select(fallback); outro provider? tenta uma vez
                AI_THINKING {model, fallbackFrom, reason}
          não → AI_ERROR
  AI_RESPONSE final com provider, modelo, consumo (exato ou estimado)
  e, se houve reserva, de quem ela substituiu e por quê
```

## 7. Interfaces

Configuração:

```toml
[ai.providers.anthropic]
type    = "anthropic"
api_key = "env:ANTHROPIC_API_KEY"

[ai.providers.ollama]
type     = "openai-compatible"
base_url = "http://127.0.0.1:11434/v1"      # loopback → local

[ai.providers.openai]
type     = "openai-compatible"
base_url = "https://api.openai.com/v1"
api_key  = "env:OPENAI_API_KEY"

[ai.roles]
conversation = { provider = "anthropic", model = "claude-opus-5", effort = "high" }
fallback     = { provider = "ollama",    model = "qwen2.5:7b" }
```

Em `zordon-ai`:

```java
record SecretRef(String variable)                 // "env:NOME"; literal é recusado
record ProviderConfig(String id, ProviderType type, URI baseUrl, SecretRef apiKey, String maxTokensParam)
record AiSettings(Map<String, ProviderConfig> providers, ModelPolicy roles)
record ModelPolicy.ModelChoice(String provider, String model, Effort effort)   // effort pode faltar
final class ProviderRegistry                      // Optional<Selection> select(ModelRole)
final class OpenAiCompatibleProvider implements AiProvider
```

Mudanças em contratos existentes:

- `AiRequest.effort` pode faltar (`effortIfAny()`): ausente significa "o padrão do
  provider". Mandar esforço a um modelo que não raciocina é erro 400 na OpenAI.
- `AiResponse.usageEstimated`: verdadeiro quando o consumo foi estimado.

## 8. Eventos

Nenhum evento novo. Campos novos em eventos existentes do tópico `chat`:

| Evento | Campo | Significado |
|---|---|---|
| `AI_THINKING` | `provider` | quem vai responder |
| `AI_THINKING` | `fallbackFrom`, `reason` | presentes quando é a reserva entrando |
| `AI_RESPONSE` final | `provider` | quem respondeu |
| `AI_RESPONSE` final | `usage.estimated` | consumo estimado, não medido |
| `AI_RESPONSE` final | `fallbackFrom`, `fallbackReason` | presentes quando quem respondeu foi a reserva |

Campo opcional novo não quebra cliente antigo ([ZWP §11](../../api/zwp-protocol.md#11-compatibilidade-e-evolução)).

## 9. Dados

`~/.zordon/config.toml`, lido na inicialização. Nenhum segredo: só referências.
O instalador copia `packaging/wsl/config.toml.example` para lá **apenas se o
arquivo não existir** — nunca sobrescreve a configuração do usuário.

`~/.zordon/secrets.env` passa a poder ter uma linha por provider.

## 10. Segurança

- **Que `Effect` produz?** `NETWORK`, para o endereço configurado. Nenhum outro.
- **Risco, e varia por argumento?** Não há ação neste marco.
- **Superfície nova para conteúdo não confiável?** Sim: a saída de qualquer
  provider configurado, tratada exatamente como a da Anthropic — texto a exibir,
  nunca comando.
- **Toca segredo?** As chaves, e esta é a decisão central: **o arquivo de
  configuração nunca contém segredo**. Ele guarda `env:NOME`, e um valor literal
  faz a leitura recusar aquele provider com uma mensagem que diz por quê. Isso
  mantém `config.toml` seguro para copiar, versionar num dotfiles ou colar num
  pedido de ajuda.
- **Ação autônoma?** A reserva é a única decisão que o núcleo toma sozinho, e ela
  nunca é silenciosa: o evento diz quem respondeu e por quê.

**"Local" é verificado, não declarado.** Só endereço de loopback conta como
local. A partir do M3, isso decide se conteúdo confidencial pode ir para o
provider; deixar o usuário declarar `local = true` para um servidor na rede
transformaria um erro de configuração em vazamento.

**A reserva pode mudar o destino dos dados.** Se a conversa vai para um provider
local e a reserva é remota, uma falha local manda a mensagem para fora da máquina.
Hoje a conversa não carrega conteúdo classificado; a partir do M3, o
`PermissionEngine` precisa avaliar a reserva como um destino novo.

## 11. Permissões

Não se aplica: nenhuma ação com efeito neste marco.

## 12. Observabilidade

| Sinal | Onde |
|---|---|
| `provider <id>: configurado` / `indisponível: <motivo>` | log, na inicialização — "configurado" não prova que a chave vale; o primeiro turno ou `set-api-key.sh --check` prova |
| Provider e modelo que responderam | rodapé da resposta e `AI_RESPONSE` |
| Consumo estimado | `≈` no rodapé; `usage.estimated` no evento |
| Reserva acionada | rodapé e log, com o motivo |

## 13. Casos de erro

| Falha | Comportamento | O que o usuário vê |
|---|---|---|
| `config.toml` com erro de sintaxe | Núcleo sobe com o padrão | log dizendo a linha do erro |
| Chave literal no `config.toml` | Aquele provider fica indisponível | "config.toml não guarda segredo: use env:NOME" |
| Papel aponta para provider inexistente | Papel sem provider | erro do turno nomeando o papel e o provider |
| Variável da chave não definida | Provider indisponível | "defina OPENAI_API_KEY" |
| Servidor não responde (Ollama parado) | `UNAVAILABLE`, com o endereço | "nada respondendo em http://127.0.0.1:11434 — o servidor está rodando?" |
| Modelo inexistente no servidor | `INVALID_REQUEST` com a mensagem do servidor | o texto do próprio servidor |
| Cota esgotada (`insufficient_quota`) | `QUOTA_EXHAUSTED` | "sem crédito" e onde resolver |
| Principal falha, sem reserva configurada | `AI_ERROR` do principal | o erro do principal |
| Principal falha depois de começar a responder | Sem reserva | o erro; o texto parcial fica na tela |
| Reserva também falha | `AI_ERROR` da reserva, citando a falha do principal | as duas causas |

## 14. Testes

| Arquivo | Nível | Cobre |
|---|---|---|
| `AiSettingsTest` | unidade | leitura, padrão sem arquivo, segredo literal, papel órfão |
| `ProviderRegistryTest` | unidade | seleção, indisponibilidade com motivo, "local" por loopback |
| `AiProviderContract` | contrato | o que **todo** provider precisa honrar, contra um servidor falso |
| `AnthropicContractTest` | contrato | o kit aplicado ao adaptador Anthropic |
| `OpenAiCompatibleContractTest` | contrato | o kit aplicado ao adaptador compatível |
| `ChatCompletionsRequestsTest` | unidade | formato do pedido: mensagens, ferramentas, esforço opcional |
| `TurnManagerTest` | unidade | reserva: quando entra, quando não entra, o que publica |
| `ArchitectureTest` | arquitetura | núcleo sem adaptador; SDK só no adaptador |

O kit de contrato fala com um **servidor HTTP falso**, na porta local, que devolve
respostas gravadas no formato de cada fornecedor. Nenhum teste depende de rede
externa ([Testes §11](../../testing/strategy.md#11-casos-de-erro)).

## 15. Critérios de aceite

- `CA-1` Dado nenhum `config.toml`, então o papel `conversation` é atendido pela
  Anthropic com a `ANTHROPIC_API_KEY`, como no M1.
- `CA-2` Dado um `config.toml` com providers e papéis, então cada papel resolve
  para o provider, o modelo e o esforço configurados.
- `CA-3` Dada uma chave escrita literalmente no `config.toml`, então o provider é
  recusado com uma mensagem que manda usar `env:NOME`.
- `CA-4` Dado um provider cujo endereço não é de loopback, então ele nunca é
  tratado como local, e um de loopback sempre é.
- `CA-5` Dado um provider em streaming, então os fragmentos chegam em ordem e o
  texto final é a concatenação deles — em todo provider.
- `CA-6` Dado um streaming em andamento, quando ele é cancelado, então a conexão
  é abortada e o resultado vem com `CANCELLED` — em todo provider.
- `CA-7` Dada uma falha HTTP, então todo provider a traduz para a mesma
  categoria: 401 → `NO_CREDENTIALS`, 429 → `RATE_LIMITED`, 5xx →
  `UNAVAILABLE`, servidor fora do ar → `UNAVAILABLE` com o endereço.
- `CA-8` Dado um provider que informa o consumo, então ele é usado; dado um que
  não informa, então o consumo é estimado e marcado como estimado.
- `CA-9` Dada uma falha do provider principal por indisponibilidade, limite,
  crédito ou chave antes do primeiro fragmento, então a reserva responde uma vez,
  e o evento final diz de quem ela substituiu e por quê.
- `CA-10` Dada uma falha depois do primeiro fragmento, ou por pedido inválido,
  então não há reserva.
- `CA-11` Dada qualquer classe do núcleo, então ela não depende de adaptador nem
  de SDK de fornecedor; e o SDK da Anthropic não aparece fora do adaptador dele.
- `CA-12` Dado um papel sem esforço configurado, então nenhum esforço é enviado
  ao provider; com esforço configurado, ele é traduzido para o parâmetro daquele
  protocolo.
- `CA-13` Dadas mensagens com ferramentas e resultados de ferramenta, então o
  adaptador compatível as traduz para o formato de funções do protocolo OpenAI.

## 16. Impacto em outros módulos

- `zordon-ai`: `AiRequest.effort` opcional; `AiResponse.usageEstimated`;
  `ModelPolicy.ModelChoice` ganha `provider`.
- `zordon-core`: `TurnManager` recebe o registro em vez de um provider único.
- `zordon-desktop`: rodapé mostra provider, `≈` e reserva.
- `packaging/wsl`: `set-api-key.sh` multi-provider; exemplo de `config.toml`.
- Documentação: [Core §1](design.md#1-abstração-de-provider),
  [Interfaces §2](../../api/core-interfaces.md#2-aiprovider),
  [Instalação §6](../../operations/install.md#6-configuração),
  [Primeiros passos](../../operations/quickstart.md), `.env.example`.

## 17. Dependências

- [ADR-0026](../../adr/ADR-0026-provider-agnostico.md) — núcleo agnóstico de provider
- [SPEC-003](SPEC-003-chat-com-streaming.md) — conversa com streaming
- [Segurança §5](../../security/model.md#5-segredos) — segredos
