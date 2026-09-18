---
document: spec-mcp-design
module: mcp
section: client
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [mcp,tools,registry,selecao-semantica]
specId: null
---

# MCP e ferramentas

## 1. Papel do Zordon

O Zordon é **MCP Client**. Ele se conecta a servidores MCP, descobre o que eles
oferecem e expõe isso ao modelo por um registro unificado.

Ser MCP *Server* (expor o Zordon a outros) está fora de escopo no v1: isso
inverteria o modelo de ameaça, criando um caminho para que um processo externo
peça ações na máquina. Se um dia fizer sentido, exige ADR próprio.

## 2. `McpManager`

```text
   config.toml / ~/.zordon/mcp.toml
            │
            ▼
   ┌──────────────────────────────────┐
   │ McpManager                       │
   │                                  │
   │  para cada servidor:             │
   │    conecta (assíncrono)          │
   │    initialize + capabilities     │
   │    list_tools / list_resources   │
   │              / list_prompts      │
   │    calcula embeddings            │
   │    registra no ToolRegistry      │
   │    monitora saúde                │
   └──────────┬───────────────────────┘
              ▼
       evt MCP_CONNECTED {server, tools: 12, ...}
```

Configuração:

```toml
[[mcp.server]]
name      = "docker"
transport = "stdio"
command   = "docker-mcp-server"
args      = []
autostart = true
trust     = "known"        # known | new
riskFloor = "GREEN"        # piso de risco para as ferramentas deste servidor

[[mcp.server]]
name      = "filesystem"
transport = "stdio"
command   = "mcp-server-filesystem"
args      = ["D:/projetos"]
riskFloor = "YELLOW"
```

`riskFloor` é essencial: o servidor MCP declara o nome e a descrição das suas
ferramentas, mas **não** é ele quem decide o risco delas. Um servidor malicioso
ou mal escrito poderia declarar `deleteEverything` como inofensiva. O piso vem da
nossa configuração; o escalonamento por argumento ([Segurança §2](../../security/model.md#escalonamento-por-argumento))
vem por cima.

Transportes suportados: `stdio` (padrão, processo filho) e `http`/SSE (para
servidores que já rodam). `stdio` é preferível por não abrir porta.

### Ciclo de vida

| Fase | Comportamento |
|---|---|
| Inicialização | Conexão **assíncrona**. O núcleo fica pronto sem esperar nenhum MCP |
| Falha ao conectar | Retenta com backoff: 5 s, 15 s, 60 s, 5 min (teto) |
| Queda em uso | `MCP_DISCONNECTED`; ferramentas saem do registro; reconecta |
| Reconexão | Redescobre ferramentas; o conjunto pode ter mudado |
| Desligamento | `shutdown` educado; `SIGKILL` após 5 s |

Um servidor MCP que trava no `initialize` **não pode** atrasar a inicialização do
núcleo. Esse é o modo de falha mais comum e o mais fácil de errar: basta uma
chamada síncrona no caminho de boot para o Zordon inteiro passar a depender do
componente menos confiável do sistema.

## 3. `ToolRegistry`

Namespace único sobre as duas origens:

```text
skill:files.read            skill:windows.openApplication
skill:system.cpu            skill:dev.gitStatus
mcp:docker.listContainers   mcp:docker.logs
mcp:git.diff                mcp:filesystem.readFile
```

Os prefixos são visíveis para o modelo e aparecem no log de atividades. Além de
evitar colisão, eles dão ao usuário uma informação que importa: se a ação passou
por um componente de terceiro.

Quando um MCP server conecta:

```text
  Docker MCP conecta
        ↓
  list_tools → 12 ferramentas
        ↓
  para cada uma:
     - normaliza nome → mcp:docker.<nome>
     - aplica riskFloor
     - deriva effects a partir do schema e do nome (heurística) + override manual
     - calcula embedding da descrição (provider local)
        ↓
  ToolRegistry.registerMcpTools("docker", tools)
        ↓
  evt MCP_CONNECTED
```

A derivação de `effects` por heurística é reconhecidamente frágil; ela serve como
**ponto de partida conservador** (na dúvida, assume efeito mais perigoso), e o
usuário pode sobrescrever por ferramenta em `~/.zordon/tool-overrides.toml`.
Ferramentas novas de servidores novos são escaladas em +1 nível durante os
primeiros 7 dias — tempo para o usuário ver o que elas fazem antes de elas
virarem rotina.

### Envenenamento de MCP

O cenário realista não é um servidor malicioso desde o início — é um servidor
benigno que, depois de uma atualização, passa a expor algo perigoso. Três
defesas:

1. **A superfície declarada é registrada a cada conexão.** Se o conjunto de
   ferramentas, os schemas ou as descrições mudarem, `ai.mcp-drift` dispara HIGH
   e o usuário revisa antes que as ferramentas novas entrem em uso.
2. **O risco vem da nossa configuração, não do servidor.** Um servidor não
   consegue se declarar inofensivo; `riskFloor` é nosso.
3. **Efeito fora do declarado é `ai.capability-violation`** — CRITICAL,
   isolamento imediato, disjuntor aberto. Um MCP que declarou `READ_FS` em
   `D:\projetos` e tenta ler `~/.ssh` é contido na tentativa, não depois.

Ver [Defesa §9](../../security/defense.md#9-defesa-contra-outras-ias).

## 4. Seleção semântica

Mandar todas as ferramentas em todo prompt é caro e piora a escolha do modelo.
Com 7 MCP servers e as Skills nativas, é fácil passar de 80 ferramentas — algo
como 25.000 tokens de entrada em **cada volta** do laço.

Processo em dois estágios ([ADR-0010](../../adr/ADR-0010-selecao-semantica-de-tools.md)):

```text
  intenção do usuário + escopo do agente
              │
              ▼
  ┌──────────────────────────────────────────┐
  │ ESTÁGIO 1 — filtro duro                  │
  │  escopo do agente (include/exclude)      │
  │  teto de permissão do agente             │
  │  servidores atualmente conectados        │
  │  80 ferramentas → 30                     │
  └───────────────┬──────────────────────────┘
                  ▼
  ┌──────────────────────────────────────────┐
  │ ESTÁGIO 2 — relevância                   │
  │  BM25 sobre nome+descrição+tags          │
  │  + similaridade de cosseno (embeddings)  │
  │  fundidos por RRF                        │
  │  30 → 12                                 │
  └───────────────┬──────────────────────────┘
                  ▼
  pinned do agente ∪ top-12  →  prompt
```

Complementos:

- **Continuidade no turno.** Ferramentas já usadas no turno permanecem
  selecionadas nas voltas seguintes. Trocar o conjunto entre voltas invalida o
  cache de prompt e confunde o modelo.
- **Meta-ferramenta `tools.search`.** Sempre presente. Se o modelo achar que
  falta algo, ele busca por descrição e recebe até 5 descritores novos. Isso
  transforma um problema de recall num problema de mais uma volta — uma troca
  boa.
- **Recurso nativo do provider.** Quando o provider suportar busca de
  ferramentas do lado do servidor (ferramentas marcadas para carregamento
  diferido, com uma ferramenta de busca embutida), o adaptador pode delegar o
  estágio 2 para ele. O contrato do `ToolRegistry` não muda; é uma otimização de
  implementação. Atenção a uma regra da API: nunca marcar **todas** as
  ferramentas como diferidas — pelo menos uma precisa estar carregada.

Efeito esperado: de ~25.000 para ~4.000 tokens de entrada por volta. Com cache de
prompt bem posicionado, a maior parte disso ainda é lida do cache.

## 5. Skills locais

Contrato em [Interfaces §3](../../api/core-interfaces.md#3-zordonskill). Conjunto inicial:

```text
skill:windows.*        openApplication, closeApplication, focusWindow,
                       clipboardRead, clipboardWrite, screenshot, notify
skill:files.*          read, write, append, search, directorySearch,
                       stat, move, quarantine        ← não existe "delete"
skill:dev.*            gitStatus, gitLog, gitDiff, gitCommit, gitBranch,
                       mavenBuild, gradleBuild, npmScript, dockerPs,
                       dockerLogs, processList, processSuspend
skill:system.*         cpu, ram, gpu, disk, network, processes,
                       services, wslInfo
skill:memory.*         search, remember, forget
skill:automation.*     create, list, pause, delete
skill:tools.search     meta-ferramenta de descoberta
```

Riscos base: `files.quarantine`, `processSuspend` e `gitCommit` nascem RED ou
YELLOW conforme a tabela de
[Segurança §2](../../security/model.md#2-classificação-de-risco); todo o resto de leitura é
GREEN.

Note o que **não** está na lista: não existe `files.delete`, não existe
`processKill` (encerrar processo é RED e exige o usuário; a Skill oferece
*suspender*, que é reversível), e não existem `git clean`, `mvn clean` nem
`docker prune` — são exclusão com outro nome
([ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md)).

### Skill vs MCP: quando usar qual

| Use Skill quando | Use MCP quando |
|---|---|
| A operação precisa do `WindowsBridge` | Já existe um servidor bom e mantido |
| O risco precisa de classificação fina por argumento | A integração é padrão (Git, Docker, filesystem) |
| A performance importa (chamada in-process) | Você quer isolamento de processo |
| É específico do Zordon | Terceiros mantêm e atualizam |

A regra prática: **prefira MCP quando existe, escreva Skill quando o Windows ou
o risco estão envolvidos.**

## 6. Isolamento de falhas

Um MCP server é um processo de terceiro. Ele vai travar, vazar memória e devolver
lixo. O núcleo precisa continuar.

| Proteção | Valor padrão |
|---|---|
| Timeout por chamada | 30 s (configurável por servidor) |
| Timeout de `initialize` | 10 s |
| Disjuntor | 5 falhas em 60 s → abre por 5 min |
| Teto de resposta | 10 MB; acima disso trunca e reporta |
| Validação de resposta | Contra o schema declarado; resposta inválida = falha |
| Limite de memória do processo | `cgroup` quando disponível |
| Reinício automático | Sim, com backoff; após 10 falhas seguidas, desabilita e alerta |
| Violação de capacidade | Isolamento imediato + disjuntor aberto + notificação CRITICAL |
| Mudança de superfície entre conexões | `ai.mcp-drift` → HIGH, requer revisão do usuário |

Com o disjuntor aberto, as ferramentas daquele servidor saem da seleção e o
modelo simplesmente não as vê. Ele não fica tentando chamar algo quebrado.

Timeout de ferramenta **nunca** é infinito e nunca excede o tempo de parede
restante do agente.

## 7. Adicionando capacidade sem tocar no núcleo

Este é o teste do princípio 3. Os três caminhos:

**Novo MCP server:** acrescentar um bloco em `~/.zordon/mcp.toml`, mandar
`mcp.connect`. As ferramentas aparecem. Zero código.

**Nova Skill:** implementar `ZordonSkill`, declarar via `ServiceLoader`. No M3 é
in-process e exige rebuild do módulo de skills (não do núcleo); a evolução para
plugin carregado de `~/.zordon/skills/` está em
[ADR-0012](../../adr/ADR-0012-skills-in-process-primeiro.md).

**Novo agente:** criar `~/.zordon/agents/<nome>.toml`. Zero código.

Se, ao adicionar uma capacidade, você precisar alterar `zordon-core`, isso é um
sinal de que o ponto de extensão está faltando — e o conserto é criar o ponto de
extensão, não abrir exceção.
