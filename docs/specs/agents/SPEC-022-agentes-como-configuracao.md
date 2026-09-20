---
document: spec-022
module: agents
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,agentes,toml,orcamento,delegacao,disjuntor,m5]
specId: SPEC-022
---

# SPEC-022 — Agentes como configuração

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `ArchitectureAgent` |
| **Revisores** | SecurityAgent, JavaAgent |
| **Marco** | M5 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

Criar um agente passa a ser criar um arquivo TOML. Cada agente tem prompt,
escopo de ferramentas, teto de permissão, modelo e orçamento, sobre o mesmo laço
de ferramentas da SPEC-019 ([Agentes](design.md)).

## 2. Problema

Só existe o agente geral. Todo pedido recebe o mesmo prompt, as mesmas
ferramentas e o mesmo teto. Não há como dar a um agente de pesquisa um teto
GREEN, nem como um pedido de vários passos respeitar um orçamento próprio.

## 3. Escopo

**Definição**

Os agentes vêm de dois lugares: os embutidos (`resources/agents/*.toml`) e os do
usuário (`~/.zordon/agents/*.toml`). O do usuário com o mesmo `id` vence. A pasta
é relida a cada `agent.list` e a cada execução: agente novo não exige reiniciar.

```toml
id          = "developer"                   # [a-z0-9-], até 40
name        = "DeveloperAgent"
description = "Git, builds, testes e logs"
prompt      = """Você é o DeveloperAgent…"""

[tools]
include = ["git.*", "build.*", "fs.*", "docker.*", "mcp.git.*"]
exclude = []
pinned  = ["git.status", "fs.read"]

[permissions]
ceiling = "YELLOW"                          # GREEN | YELLOW | RED

[model]
role = "agent_heavy"                        # sem ele configurado: conversation

[budget]
maxSteps     = 15
maxToolCalls = 25
maxTokens    = 120000
wallClock    = "PT5M"
```

- `include` e `exclude` aceitam `*` e os prefixos da documentação (`skill:`,
  `mcp:`).
- Arquivo inválido é ignorado com o motivo em `agent.list`. Um arquivo ruim não
  derruba os outros.
- Embutidos: `zordon` (geral, YELLOW), `system` (YELLOW), `developer` (YELLOW),
  `research` (**GREEN**) e `automation` (YELLOW). O molde `projeto-<nome>` está
  documentado; o usuário cria o dele.

**Escolha do agente**

- "pergunta pro developer: …", "pede pro research …" e "developer, …" escolhem o
  agente. O `chat.send` também aceita `{agent}`. Forçar sempre vence.
- Sem escolha, o turno é do `zordon`. Agente desconhecido cai no `zordon`, com o
  aviso "não conheço o agente X".

**No turno (o laço da SPEC-019)**

- **Prompt de sistema:** o do Zordon mais o do agente. É estável por agente, o
  que preserva o cache.
- **Ferramentas:** o escopo filtra o que é oferecido e o que pode ser chamado.
  Ferramenta fora do escopo vira erro para o modelo, sem executar.
- **Teto:** entra no `PolicyContext`, e o motor nega acima dele sem perguntar.
  Ferramenta com risco base acima do teto nem é oferecida.
- **Orçamento:** passos, chamadas, tokens (entrada + saída somados) e tempo de
  parede. Estourou: o turno termina com o parcial e o motivo.
- **Modelo:** o papel do agente e, sem ele, `conversation`. A reserva segue
  valendo.

**Delegação (`agent.delegate {agent, task}`)**

- Só o agente do turno delega, e só se o escopo dele incluir `agent.delegate`
  (o geral inclui, com `*`). O sub-agente não recebe a ferramenta:
  profundidade máxima 2.
- O filho gasta do orçamento restante do pai.
- Teto do filho: o menor entre o dele e o do pai. O principal do filho sai com
  `delegated = true`, e o motor sobe um nível.
- O resultado volta como dado, com a mesma marca de resultado de ferramenta.
- Cada delegação gera `AGENT_STARTED`, `AGENT_PROGRESS` e `AGENT_FINISHED`, com
  `parent`.

**Disjuntor por execução** ([Agentes §5](design.md#5-circuit-breaker-de-agente))

A execução é suspensa (`SUSPENDED_BY_BREAKER`) quando acontece:
- 3 ações negadas em 60 s;
- 1 tentativa acima do teto;
- 5 chamadas idênticas seguidas.

Ao suspender, o turno termina com o motivo e sai uma notificação HIGH com os
sinais. Não há nova tentativa sozinha.

**Execução em segundo plano:** `agent.run {agent, task}` roda sem streaming.
Devolve `runId` na hora; o resultado chega em `AGENT_FINISHED {text}`.
`agent.cancel {runId}` cancela.

**Ferramentas de leitura para o UC4:** `docker.ps` e `docker.logs {container,
tail≤500}` (GREEN, pelo `ProcessRunner`, com `docker` do catálogo). A saída de
logs é conteúdo externo e contamina o turno.

## 4. Não escopo

- Classificação do agente por modelo (o roteador por modelo pequeno): por
  enquanto, só a escolha explícita e o geral.
- Disjuntor por sequência anômala (`ai.tool-sequence`) e por consumo 3× a
  mediana: M7, com o `DetectionEngine`.
- Planner e plano durável: [SPEC-023](SPEC-023-planos-duraveis-e-verificacao.md).
- Tela de agentes completa: a lista entra nos ajustes; a árvore de execução fica
  para o M8.

## 5. Arquitetura

```text
~/.zordon/agents/*.toml + embutidos ─► AgentRegistry ─► AgentProfile
                                                       │
IntentRouter ("pergunta pro X") ─► TurnManager ─► laço SPEC-019 com o perfil
                                                       │  offer/call com AgentScope
                                                       ▼
                                   ModelToolCaller ─► SkillRuntime ─► Gatekeeper (teto no PolicyContext)
                                                       │
                              agent.delegate ─► AgentRunner (sem streaming, orçamento do pai)
```

## 6. Fluxo

UC4, "developer, veja por que minha API caiu":

1. O roteador escolhe o `developer`.
2. O turno usa o prompt do `developer`, as ferramentas `docker.*`, `git.*` e
   `fs.*`, e o teto YELLOW.
3. O modelo chama `docker.ps`, depois `docker.logs {container: "api"}`. Os logs
   voltam como dado e o turno fica contaminado.
4. Na terceira volta, ele responde com a causa. O orçamento (15 passos, 25
   chamadas) é respeitado; se estourar, sai o parcial com o motivo.

## 7. Interfaces

```java
public record AgentProfile(String id, String name, String description, String prompt, ToolScope tools,
        RiskLevel ceiling, ModelRole role, Budget budget, String source) {}
public record Budget(int maxSteps, int maxToolCalls, long maxTokens, Duration wallClock) {}
public final class AgentRegistry { List<AgentProfile> list(); Optional<AgentProfile> find(String id); }
```

| Método ZWP | Params | Retorno |
|---|---|---|
| `agent.list` | `{}` | `{agents[{id, name, description, ceiling, source}], invalid[{file, reason}]}` |
| `agent.run` | `{agent, task}` | `{runId}` |
| `agent.cancel` | `{runId}` | `{cancelled}` |
| `agent.runs` | `{}` | `{runs[{runId, agent, task, state, steps}]}` |

## 8. Eventos

`AGENT_STARTED {runId, agent, task, parent?}`, `AGENT_PROGRESS {runId, step,
note}` e `AGENT_FINISHED {runId, ok, reason, durationMs, usage, text?}`, no tópico
`agents`.

## 9. Dados

Nada persistido nesta SPEC. As execuções vivem na RAM; o plano durável é da
SPEC-023.

## 10. Segurança

- **O teto é trava dura** no motor (núcleo de confiança), não uma sugestão no
  prompt.
- **O `research` tem teto GREEN:** mesmo convencido por conteúdo malicioso, não
  escreve, não executa e não chama o host.
- **Delegar nunca eleva:** o teto é o mínimo dos dois, o risco sobe um nível
  (`delegated`), e o orçamento é o do pai.
- **O teto vale sobre o risco da própria ação** (caminhos, alvos, segredo,
  comando). O nível que a origem acrescenta (delegação, automação sem usuário)
  decide só se o usuário confirma. Sem essa separação, um `research` delegado,
  de teto GREEN, não conseguiria nem ler.
- **Arquivo de agente é configuração do usuário:** o Zordon não escreve em
  `~/.zordon/agents/` (a auto-modificação de agentes é do M8, ADR-0034).

## 11. Permissões

- `agent.delegate`: GREEN em si; o que o filho faz passa pelo motor com o teto
  e o `delegated`.
- `docker.ps` e `docker.logs`: GREEN, `READ_FS`.
- `agent.run`: quem pede é a origem do cliente (UI).

## 12. Observabilidade

- Log INFO por execução: agente, passos, chamadas, tokens, duração e motivo do
  término.
- Ferramentas **oferecidas** também vão para o log (DEBUG), porque é aí que se
  responde "por que não usou a ferramenta certa?".

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| TOML inválido | Ignorado; motivo em `agent.list` |
| Dois arquivos com o mesmo `id` | Vale o do usuário; entre dois do usuário, o primeiro em ordem alfabética, com aviso |
| Papel do modelo sem provider | `conversation` |
| Orçamento estourado | Parcial com "Parei no limite de <passos\|chamadas\|tokens\|tempo> do agente X." |
| Delegação a agente inexistente | Erro para o modelo, sem executar |

## 14. Testes

- Registro: embutidos, arquivo do usuário sobrepõe, inválido listado, pasta
  relida.
- Turno: prompt e ferramentas do agente, fora do escopo vira erro, teto nega sem
  perguntar, orçamento de passos, de tokens e de tempo.
- Delegação: sub-agente sem `agent.delegate`, orçamento compartilhado, teto
  mínimo, `delegated` sobe o risco, eventos com `parent`.
- Disjuntor: 3 negadas, acima do teto e 5 idênticas suspendem, com notificação.
- `docker.ps` e `docker.logs` com um `docker` falso.

### Evidências (2026-09-19)

- `AgentsTest` (6 testes, laço real com provider roteirizado, motor e auditoria
  de verdade):
  - CA-1: `projeto-aurora.toml` criado com o núcleo de pé aparece na lista;
    "pergunta pro projeto-aurora: …" usa o prompt dele; o arquivo quebrado é
    listado com o motivo; o `research.toml` do usuário vence o embutido;
  - CA-2: agente de teto GREEN não recebe `fs.write`; pedido mesmo assim, o
    motor nega sem perguntar e a execução é suspensa;
  - CA-3: para no limite de passos, de tokens e de tempo, com o parcial e o
    motivo;
  - CA-4: o geral delega ao `research`, que não recebe `agent.delegate`, gasta
    do mesmo medidor e tem o teto mínimo; o resultado volta marcado como dado;
    `AGENT_STARTED` e `AGENT_FINISHED` com `parent`;
  - CA-5: 5 chamadas idênticas e 3 negações em 60 s suspendem e notificam;
  - CA-6 (UC4): "developer, veja por que minha API caiu" chama `docker ps` e
    `docker logs --tail 100 api` num `docker` falso e responde com a causa em 3
    voltas.
- `PermissionEngineTest.tetoDoAgenteNegaSemPerguntarEADelegacaoSoPedeConfirmacao`:
  o teto vale sobre o risco da ação; a delegação só leva à confirmação. A tabela
  golden segue passando.

## 15. Critérios de aceite

- `CA-1` Dado um `.toml` novo em `~/.zordon/agents/`, então ele aparece em
  `agent.list` e atende "pergunta pro <id>: …" sem reiniciar nem mudar código.
- `CA-2` Dado um agente com teto GREEN, então ferramenta YELLOW não é oferecida
  e, se pedida, é negada pelo motor sem perguntar ao usuário.
- `CA-3` Dado um orçamento, então o turno para no limite de passos, chamadas,
  tokens ou tempo, com o parcial e o motivo.
- `CA-4` Dada uma delegação, então o sub-agente não delega de novo, gasta do
  orçamento do pai, tem o teto mínimo, e o resultado volta como dado.
- `CA-5` Dado um agente com 3 negações em 60 s, uma tentativa acima do teto ou 5
  chamadas idênticas, então a execução é suspensa e o usuário é notificado.
- `CA-6` Dado "developer, veja por que minha API caiu", então o agente percorre
  `docker.ps` e `docker.logs` e responde dentro do orçamento (UC4).

## 16. Impacto em outros módulos

- `zordon-security`: `PolicyContext.ceiling` e a negação acima do teto.
- `zordon-core`: pacote `agents`; `TurnManager` com perfil; `SkillRuntime` e
  `ModelToolCaller` com escopo; `IntentRouter` com a escolha de agente;
  `ProcessTools.docker*`.
- `zordon-api`: `AGENT_STARTED`, `AGENT_PROGRESS`, `AGENT_FINISHED`.
- `zordon-desktop`: lista de agentes nos ajustes.

## 17. Dependências

- [Agentes — design](design.md) · [Catálogo](../../agents/catalog.md) ·
  [SPEC-019](../core/SPEC-019-ferramentas-pelo-modelo.md) ·
  [ADR-0021](../../adr/ADR-0021-dois-perfis-de-agente.md)
