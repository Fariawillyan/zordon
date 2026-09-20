---
document: spec-020
module: mcp
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,mcp,ferramentas,isolamento,drift,m4]
specId: SPEC-020
---

# SPEC-020 — Cliente MCP

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `McpAgent` |
| **Revisores** | SecurityAgent, ArchitectureAgent |
| **Marco** | M4 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

Acrescentar capacidade sem tocar no núcleo: um servidor MCP declarado no
`config.toml` tem as ferramentas oferecidas ao modelo pelo laço da SPEC-019, com
o risco decidido por nós e não pelo servidor ([MCP §2–§6](design.md)).

## 2. Problema

As ferramentas do Zordon estão todas compiladas no núcleo. Docker, Git, bancos e
serviços da rede exigiriam código novo para cada um.

## 3. Escopo

**Configuração**

```toml
[[mcp.server]]
name      = "docker"
command   = ["docker", "run", "-i", "--rm", "mcp/docker"]   # programa do catálogo, sem shell
risk_floor = "GREEN"          # o piso é nosso; o servidor não se declara inofensivo
autostart = true
```

**Transporte `stdio`** (JSON-RPC 2.0, uma mensagem por linha)

- O processo é longo: `ProcessRunner.start` (SPEC-016) com uma `Permit` de
  `mcp.start`, programa do catálogo, ambiente mínimo e sem shell.
- Handshake: `initialize` (10 s), `notifications/initialized`, `tools/list`
  (paginado).
- Chamada: `tools/call`, com 30 s por chamada e resposta cortada em 1 MB.

**Registro**

- As ferramentas entram no `SkillRuntime` como `mcp.<servidor>.<ferramenta>`.
- **Risco:** o piso é `risk_floor`. As anotações do servidor só sobem o risco
  (`destructiveHint` → RED; `openWorldHint` → efeito `NETWORK`), nunca descem.
- **Primeira semana:** ferramenta vista pela primeira vez há menos de 7 dias sobe
  um nível.
- **Mudança de superfície** (drift):
  - o hash de nomes, descrições e esquemas é guardado por servidor;
  - se mudar, as ferramentas daquele servidor não entram, e sai um aviso HIGH;
  - `mcp.approve {server}` (só da tela) aceita a superfície nova.
- **Disjuntor:** 5 falhas em 60 s abrem por 5 min, e as chamadas são negadas até
  fechar.

**Ciclo de vida**

- Conexão assíncrona: o núcleo fica pronto sem esperar nenhum servidor.
- Queda: as ferramentas saem do registro e a reconexão segue com espera de 5 s,
  15 s, 60 s e 5 min.
- Encerramento: fechar a entrada e, depois de 5 s, encerrar o processo.

**ZWP:** `mcp.servers` (estado, ferramentas e erro de cada servidor) e
`mcp.approve {server}`.

## 4. Não escopo

- Transporte HTTP/SSE: próxima SPEC, quando houver um servidor que precise.
- Recursos (`resources/*`) e prompts (`prompts/*`): entram com a memória (M5).
- Seleção semântica: M5. Aqui vale a oferta por palavras da SPEC-019.

## 5. Arquitetura

```text
config.toml [[mcp.server]] ─► McpManager ─► ProcessRunner.start (Permit mcp.start, auditado)
                                  │                 │ stdin/stdout JSON-RPC
                                  ▼                 ▼
                           McpClient (initialize, tools/list, tools/call, timeouts)
                                  │
                                  ▼
                       SkillRuntime.register(mcp.<servidor>.<ferramenta>) ─► laço da SPEC-019
```

## 6. Fluxo

1. Na inicialização, cada servidor `autostart` é iniciado em segundo plano.
2. `initialize` e `tools/list` → hash da superfície → confere com o guardado.
3. Superfície igual (ou primeira vez) → as ferramentas entram.
4. O modelo pede `mcp_docker_list_containers` → `Gatekeeper` (piso + escalonamento)
   → `tools/call` → resultado como dado (SPEC-019).

## 7. Interfaces

```java
public final class ProcessRunner {              // zordon-security, novo
    Live start(Gatekeeper.Permit.Granted permit, Path cwd);   // processo longo, stdin/stdout
}
public final class McpClient {                  // zordon-core
    CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params, Duration timeout);
}
```

| Método ZWP | Params | Retorno |
|---|---|---|
| `mcp.servers` | `{}` | `{servers[{name, state, tools, error?, drift}]}` |
| `mcp.approve` | `{server}` | `{approved}`; só de um desktop |

## 8. Eventos

`SYSTEM_ALERT` (conectou, caiu ou desabilitou) e a notificação HIGH de drift
pelo `NotificationCenter`. `MCP_CONNECTED` e `MCP_DISCONNECTED` ficam para o
tópico `mcp` quando houver tela própria.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Superfície aprovada por servidor | `~/.zordon/state/mcp-surface.json` | Até mudar e ser aprovada de novo |
| Primeira vez que cada ferramenta foi vista | `~/.zordon/state/mcp-seen.json` | Permanente |

## 10. Segurança

- **Servidor é código de terceiro:** roda como processo filho, com ambiente
  mínimo, pelo mesmo caminho auditado de qualquer processo.
- **O risco é nosso:** `risk_floor` na nossa configuração; o servidor só consegue
  subir o risco das próprias ferramentas.
- **Drift bloqueia:** ferramenta que mudou não entra sem o usuário aprovar na
  tela.
- **Resultado é dado:** contamina o turno (SPEC-019 CA-4).

## 11. Permissões

- `mcp.start`: GREEN com o programa do catálogo. Declarar o servidor no
  `config.toml` é o ato deliberado do usuário, como o catálogo de aplicativos;
  programa fora do catálogo vira RED e pergunta na tela. Assim o `autostart` no
  boot não depende de uma janela aberta.
- As ferramentas seguem o piso e o motor.

## 12. Observabilidade

- Log INFO de conexão, ferramentas registradas, queda e disjuntor.
- `mcp.servers` alimenta a tela.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Programa fora do catálogo | Servidor `failed`, com o motivo; não tenta de novo até reiniciar |
| `initialize` em mais de 10 s | Processo encerrado; nova tentativa com espera |
| Chamada em mais de 30 s | Falha da ferramenta, contada no disjuntor |
| Resposta maior que 1 MB | Descartada sem ir inteira para a memória; a chamada dela falha na hora, com aviso |
| JSON inválido do servidor | Linha ignorada, com aviso; três seguidas derrubam a conexão |

## 14. Testes

Um servidor MCP falso (Python, só biblioteca padrão) no catálogo de teste:
- handshake, listagem e chamada;
- ferramenta que trava (prazo);
- superfície que muda (drift);
- anotação destrutiva (sobe para RED);
- queda e reconexão.

### Evidências (2026-09-19)

- `McpManagerTest` (10 testes) contra `src/test/resources/mcp/fake_mcp.py`, um
  servidor MCP de verdade por stdio, iniciado por `python3` do catálogo pelo
  `Gatekeeper` + `ProcessRunner.start`:
  - CA-1: `start()` volta em menos de 200 ms; handshake, `tools/list` em duas
    páginas, seis ferramentas `mcp.fake.*` registradas; `echo` responde; a
    auditoria tem `mcp.start` e `mcp.fake.echo`, e a cadeia verifica;
  - CA-2: piso GREEN com leitura declarada → GREEN; sem anotação → YELLOW;
    `destructiveHint` → RED, perguntado e negado; piso YELLOW com leitura
    declarada continua YELLOW; ferramenta nova sobe um nível;
  - CA-3: a descrição de uma ferramenta muda → estado `drift`, nenhuma ferramenta
    registrada, aviso HIGH; `approve` registra e grava a superfície nova;
  - CA-4: chamada que trava termina no prazo (curto no teste); 5 erros abrem o
    disjuntor, a chamada seguinte é recusada, e 5 min depois (relógio de teste)
    volta a funcionar;
  - CA-5: o servidor sai no meio de uma chamada → a chamada termina com erro, as
    ferramentas saem, a reconexão acontece e as ferramentas voltam;
  - programa fora do catálogo → RED perguntado, negado, `failed` sem nova
    tentativa; o bloco do `config.toml.example` descomentado é válido.
- Desktop: `PermissionUiTest.servidorMcpQueMudouMostraAprovarEOBotaoPedeAoNucleo`.
- Pendente: um servidor real de terceiros (o de Docker do roadmap). Escolher qual
  servidor rodar é decisão de cadeia de suprimentos do owner
  ([supply-chain](../../security/supply-chain.md)); o Zordon não baixou nenhum.

## 15. Critérios de aceite

- `CA-1` Dado um servidor declarado, então ele é iniciado pelo caminho mediado, o
  handshake acontece, e as ferramentas entram como `mcp.<servidor>.<ferramenta>`
  sem atrasar o núcleo.
- `CA-2` Dada uma chamada, então ela passa pelo `Gatekeeper` com o piso da
  configuração, sobe com as anotações e nunca desce abaixo do piso.
- `CA-3` Dada uma superfície diferente da aprovada, então as ferramentas não
  entram, sai um aviso HIGH, e só `mcp.approve` pela tela as libera.
- `CA-4` Dado um servidor que trava ou falha 5 vezes em 60 s, então a chamada
  termina no prazo e o disjuntor abre por 5 min.
- `CA-5` Dada a queda do servidor, então as ferramentas saem do registro e a
  reconexão acontece com espera.

## 16. Impacto em outros módulos

- `zordon-security`: `ProcessRunner.start`; `npx` e `uvx` nos programas padrão.
- `zordon-core`: `zordon.core.mcp` e o `SkillRuntime` removendo ferramentas.
- `packaging/wsl/config.toml.example`: o bloco comentado.

## 17. Dependências

- [MCP — design](design.md) ·
  [SPEC-016](../security/SPEC-016-execucao-mediada-ferramentas-e-ponte-windows.md) ·
  [SPEC-019](../core/SPEC-019-ferramentas-pelo-modelo.md) ·
  [SPEC-015](../security/SPEC-015-pedido-de-permissao-notificacoes-e-kill-switch.md)
