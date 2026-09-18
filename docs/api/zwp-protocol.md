---
document: api-zwp
module: api
section: protocol
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [websocket,json-rpc,eventos,streaming]
specId: null
---

# API — ZWP: Zordon Wire Protocol v1

Protocolo único entre o núcleo e todos os clientes. Decisão e alternativas em
[ADR-0003](../adr/ADR-0003-websocket-json-rpc.md).

**Resumo:** JSON-RPC 2.0 bidirecional sobre um WebSocket, mais frames binários
para áudio e imagem. Um socket, uma porta, um protocolo.

## 1. Propriedades

| Propriedade | Valor |
|---|---|
| Transporte | WebSocket (RFC 6455), `ws://` em loopback |
| Endpoint | `ws://<host>:8777/zwp/v1` |
| Codificação de controle | JSON-RPC 2.0 em frames de texto, UTF-8 |
| Codificação de mídia | frames binários com cabeçalho de 8 bytes |
| Direção | **Bidirecional simétrico** — o núcleo também faz requisições ao cliente |
| Autenticação | `Authorization: Bearer <token>` no handshake HTTP |
| Versionamento | no path (`/zwp/v1`) + negociação no `session.hello` |
| Ordenação | garantida por socket; eventos têm `seq` monotônico |
| Compressão | `permessage-deflate` desligado (loopback; custa CPU sem ganho) |

A bidirecionalidade não é um detalhe: é o que permite o núcleo (no WSL) chamar
o Windows sem abrir porta no Windows, contornando o firewall.
Ver [Windows↔WSL §R4](../architecture/windows-wsl.md#r4--firewall-do-windows-bloqueia-wsl--windows).

## 2. Handshake e sessão

```text
cliente                                            núcleo
   │                                                 │
   │  GET /zwp/v1  Upgrade: websocket                │
   │  Authorization: Bearer <token do endpoint.json> │
   ├────────────────────────────────────────────────►│
   │                                                 │ valida token
   │                                                 │ rejeita se houver Origin
   │  101 Switching Protocols                        │
   │◄────────────────────────────────────────────────┤
   │                                                 │
   │  → session.hello                                │
   ├────────────────────────────────────────────────►│
   │  ← resultado com capabilities do núcleo         │
   │◄────────────────────────────────────────────────┤
   │                                                 │
   │  → session.subscribe (tópicos de interesse)     │
   ├────────────────────────────────────────────────►│
   │  ← eventos a partir daqui                       │
   │◄════════════════════════════════════════════════┤
```

### `session.hello`

```json
{ "jsonrpc": "2.0", "id": 1, "method": "session.hello",
  "params": {
    "client":   { "kind": "desktop", "name": "zordon-desktop", "version": "0.1.0" },
    "protocol": { "min": 1, "max": 1 },
    "capabilities": ["audio.playback", "ui.permission-prompt", "ui.notifications"],
    "resume":   { "startId": "01J9X2K7QF8ZP3", "lastEventSeq": 48210 }
  }
}
```

`kind` é `desktop`, `host`, `cli` ou `test`. Ele determina o que o cliente pode
oferecer e o que pode pedir — um `desktop` não pode registrar-se como provedor
de `WindowsBridge`; um `host` pode.

`capabilities` é o cliente declarando o que sabe fazer. É assim que o núcleo
descobre que existe um host capaz de tocar áudio ou de abrir aplicativos. Um
`host` completo declara:

```
audio.capture   audio.playback   bridge.process   bridge.clipboard
bridge.screenshot   bridge.notifications   bridge.window   power.events
```

`resume` é opcional; ver §8.

Resposta:

```json
{ "jsonrpc": "2.0", "id": 1, "result": {
    "protocol": 1,
    "core": { "version": "0.1.0", "startId": "01J9X2K7QF8ZP3", "startedAt": "..." },
    "sessionId": "s_7f3a",
    "capabilities": ["chat.stream","voice","agents","mcp","skills","memory","automation"],
    "resumed": false,
    "heartbeatIntervalMs": 10000
  }
}
```

Se `protocol.max` do cliente for menor que o mínimo suportado pelo núcleo, o
núcleo responde erro `ERR_PROTOCOL_UNSUPPORTED` e fecha com código `4400`.

## 3. Envelope

Três formas, exatamente as do JSON-RPC 2.0:

```json
// requisição (espera resposta)
{ "jsonrpc": "2.0", "id": 42, "method": "chat.send", "params": { ... } }

// resposta
{ "jsonrpc": "2.0", "id": 42, "result": { ... } }
{ "jsonrpc": "2.0", "id": 42, "error": { "code": -32001, "message": "...",
                                          "data": { "kind": "ERR_BRIDGE_UNAVAILABLE" } } }

// notificação (sem resposta) — é assim que eventos viajam
{ "jsonrpc": "2.0", "method": "event", "params": { "type": "TOOL_STARTED", "seq": 48211, ... } }
```

`id` é um inteiro por conexão, escolhido por quem faz a requisição. Como o
protocolo é simétrico, cliente e núcleo mantêm espaços de `id` **separados** —
não há colisão porque a direção desambigua.

Toda requisição tem timeout. O padrão é 30 s; métodos que sabidamente demoram
(`agent.run`, `bridge.screenshot`) declaram o seu na tabela de métodos.

## 4. Métodos — cliente → núcleo

### Conversa

| Método | Params | Retorno | Notas |
|---|---|---|---|
| `chat.send` | `{text, sessionId?, attachments?}` | `{turnId}` | Resposta chega por eventos `AI_RESPONSE` |
| `chat.cancel` | `{turnId}` | `{cancelled}` | Cancela agente, ferramentas e stream |
| `chat.history` | `{sessionId, before?, limit}` | `{messages[]}` | Paginado, mais recentes primeiro |
| `chat.newSession` | `{title?}` | `{sessionId}` | |

### Voz

| Método | Params | Retorno |
|---|---|---|
| `voice.setMode` | `{mode: "off"\|"wake"\|"push"\|"open"}` | `{mode}` |
| `voice.startListening` | `{reason: "hotkey"\|"ui"}` | `{listenId}` |
| `voice.stopListening` | `{listenId}` | `{}` |
| `voice.interrupt` | `{}` | `{}` — para o TTS em andamento (barge-in) |

### Agentes, ferramentas, MCP

| Método | Params | Retorno |
|---|---|---|
| `agent.list` | `{}` | `{agents[]}` |
| `agent.run` | `{agent, task, budget?}` | `{runId}` (timeout 10 min) |
| `agent.cancel` | `{runId}` | `{}` |
| `tool.list` | `{scope?, query?}` | `{tools[]}` |
| `tool.describe` | `{name}` | `{descriptor}` |
| `mcp.list` | `{}` | `{servers[]}` |
| `mcp.connect` / `mcp.disconnect` | `{server}` | `{status}` |
| `skill.list` | `{}` | `{skills[]}` |

Não existe `tool.invoke` exposto a clientes de UI. Ferramentas são invocadas
pelo orquestrador dentro de um turno, sob permissão. Um cliente `cli` com
capacidade `debug.invoke` pode fazê-lo, e toda invocação assim é marcada como
`actor=user-direct` na auditoria.

### Permissão

| Método | Params | Retorno |
|---|---|---|
| `permission.respond` | `{requestId, decision, scope?}` | `{}` |
| `permission.policy.get` / `.set` | `{}` / `{policy}` | `{policy}` |

`decision` é `allow`, `deny` ou `always_allow`. `scope` é `once`, `session` ou
`persistent` — e `persistent` é recusado para ações RED.

### Memória, automação, sistema

| Método | Params | Retorno |
|---|---|---|
| `memory.search` | `{query, limit, kinds?}` | `{results[]}` |
| `security.incidents` | `{since?, severities?}` | `{incidents[]}` |
| `security.respond` | `{messageId, choice}` | `{}` — resposta a "comunicar antes de agir" |
| `security.acknowledge` | `{messageId}` | `{}` — confirma leitura (obrigatório em CRITICAL) |
| `security.breakers` | `{}` | `{breakers[]}` |
| `security.breakerRelease` | `{subject, mode}` | `{}` — `mode`: `supervised` \| `closed` |
| `security.lockdown` | `{enter \| exit, reason}` | `{state}` — sair exige `OPERATOR` |
| `security.quarantine.list` | `{}` | `{items[]}` |
| `security.quarantine.restore` | `{vaultId}` | `{restored}` |
| `security.exceptions` | `{}` | `{exceptions[]}` — supressões ativas, revogáveis |
| `change.plan` | `{taskId}` | `{plan}` — plano completo para revisão |
| `change.approve` | `{taskId, decision, scopeAdjustment?}` | `{}` |
| `change.revert` | `{taskId}` | `{revertCommit}` |
| `security.policy.get` | `{}` | `{policy}` — **somente leitura; não existe `set`** |
| `memory.forget` | `{factId}` | `{}` |
| `automation.list` / `.create` / `.delete` / `.pause` | ... | ... |
| `system.metrics` | `{}` | snapshot atual |
| `system.health` | `{}` | `{status, subsystems{}}` |
| `system.diagnostics` | `{}` | dados da tela de Diagnostics |
| `session.subscribe` / `.unsubscribe` | `{topics[]}` | `{topics[]}` |
| `session.ping` | `{}` | `{serverTimeMs}` |

## 5. Métodos — núcleo → cliente

É aqui que a bidirecionalidade paga. Estes métodos são requisitados **pelo
núcleo** e respondidos pelo cliente.

Para um cliente `host`:

| Método | Params | Retorno | Timeout |
|---|---|---|---|
| `bridge.openApplication` | `{target, args[], workingDir?}` | `{pid, started}` | 15 s |
| `bridge.closeApplication` | `{pid \| processName, force}` | `{closed}` | 10 s |
| `bridge.listProcesses` | `{filter?}` | `{processes[]}` | 10 s |
| `bridge.clipboardRead` / `Write` | `{}` / `{content}` | ... | 5 s |
| `bridge.screenshot` | `{display?, region?}` | `{streamId, width, height}` → frame binário | 15 s |
| `bridge.notify` | `{title, body, urgency, actions[]}` | `{shown}` | 5 s |
| `bridge.focusWindow` | `{titlePattern \| pid}` | `{focused}` | 5 s |
| `audio.play` | `{streamId, format}` | `{accepted}` | 5 s |
| `audio.stop` | `{streamId}` | `{}` | 2 s |
| `audio.setCaptureEnabled` | `{enabled}` | `{enabled}` | 2 s |

Para um cliente `desktop`:

| Método | Params | Retorno | Timeout |
|---|---|---|---|
| `ui.requestPermission` | `{requestId, action, risk, explanation, ttlMs}` | `{decision, scope}` | 60 s |
| `ui.showOverlay` | `{state, text?}` | `{}` | 2 s |
| `ui.notify` | `{title, body}` | `{}` | 5 s |
| `ui.securityAlert` | `{messageId, severity, ...8 campos, options[]}` | `{choice?}` | 300 s |
| `ui.forceForeground` | `{reason}` | `{}` | 5 s — só para CRITICAL |

**`ui.requestPermission` é o método mais sensível do protocolo.** O que ele
exibe não é texto gerado pelo modelo: é a ação **já resolvida e classificada**
pelo núcleo. O campo `explanation` pode conter texto do modelo, mas a UI deve
renderizá-lo como citação claramente separada da descrição da ação. Sem isso, um
modelo comprometido poderia induzir o usuário a autorizar outra coisa.
Ver [UI §6](../specs/ui/design.md#6-diálogo-de-permissão).

Se nenhum cliente com a capacidade necessária estiver conectado, o núcleo falha
a operação imediatamente com `ERR_BRIDGE_UNAVAILABLE` ou, no caso de permissão,
aplica a política de ausência: `deny` para YELLOW e RED, `allow` para GREEN.

## 6. Eventos

Eventos são notificações `method: "event"`. Envelope comum:

```json
{ "jsonrpc": "2.0", "method": "event", "params": {
    "type": "TOOL_STARTED",
    "seq": 48211,
    "ts": "2026-09-17T22:31:07.412Z",
    "topic": "tools",
    "payload": { "turnId": "t_91a", "agentId": "system", "runId": "r_22",
                 "tool": "mcp:docker.listContainers", "args": {...}, "risk": "GREEN" }
  }
}
```

Os identificadores de correlação (`turnId`, `agentId`, `runId`) viajam **dentro do
`payload`**, não no envelope. A maioria dos eventos não tem turno nem execução
associada — um `SYSTEM_METRICS` não tem —, e mantê-los no envelope significaria
três campos nulos em quase todo evento
([SPEC-002 §7](../specs/core/SPEC-002-fundacao-zwp-e-nucleo.md#7-interfaces)).
Filtrar por turno na UI custa o mesmo nos dois formatos.

As chaves de um objeto JSON são serializadas em ordem determinística. Isso não é
exigência do JSON-RPC: é o que permite versionar amostras douradas e ler um diff
de protocolo.

### Catálogo

| Tópico | Evento | Payload essencial |
|---|---|---|
| `voice` | `VOICE_STARTED` | `{listenId, trigger: "wake"\|"hotkey"\|"ui"}` |
| `voice` | `VOICE_LISTENING` | `{listenId}` |
| `voice` | `VOICE_PARTIAL` | `{listenId, text}` — transcrição parcial |
| `voice` | `VOICE_STOPPED` | `{listenId, text, durationMs, confidence}` |
| `voice` | `VOICE_LEVEL` | `{rms, peak}` — coalescido, ~20 Hz, para o waveform |
| `voice` | `TTS_STARTED` / `TTS_FINISHED` | `{streamId}` |
| `chat` | `USER_COMMAND` | `{turnId, text, source: "voice"\|"text"}` |
| `chat` | `AI_THINKING` | `{turnId, model, agentId}` |
| `chat` | `AI_RESPONSE` | `{turnId, delta?, text?, done}` — streaming |
| `chat` | `AI_ERROR` | `{turnId, kind, message, retryable}` |
| `tools` | `TOOL_STARTED` | `{callId, tool, args, risk}` |
| `tools` | `TOOL_FINISHED` | `{callId, ok, durationMs, summary, error?}` |
| `agents` | `AGENT_STARTED` | `{runId, agent, task}` |
| `agents` | `AGENT_PROGRESS` | `{runId, step, of?, note}` |
| `agents` | `AGENT_FINISHED` | `{runId, ok, reason, durationMs, usage}` |
| `mcp` | `MCP_CONNECTED` | `{server, tools, resources, prompts}` |
| `mcp` | `MCP_DISCONNECTED` | `{server, reason, willRetry}` |
| `permission` | `PERMISSION_REQUIRED` | `{requestId, action, risk}` |
| `permission` | `PERMISSION_DECIDED` | `{requestId, decision, by: "user"\|"policy"\|"timeout"}` |
| `system` | `SYSTEM_METRICS` | `{cpu, ram, gpu, disk, net}` — coalescido, 1 Hz |
| `system` | `SYSTEM_ALERT` | `{severity, source, message, data}` |
| `system` | `SYSTEM_CLOCK_JUMP` | `{deltaMs}` |
| `system` | `CORE_STARTED` | `{startId, version}` |
| `automation` | `AUTOMATION_TRIGGERED` | `{automationId, name, trigger}` |
| `automation` | `AUTOMATION_FINISHED` | `{automationId, ok, summary}` |
| `memory` | `MEMORY_WRITTEN` | `{kind, id, summary}` |
| `security` | `SECURITY_FINDING` | `{findingId, severity, detector, subject, rationale}` |
| `security` | `SECURITY_ACTION_PROPOSED` | `{messageId, action, affected, reversible, ttlMs}` |
| `security` | `SECURITY_ACTION_TAKEN` | `{eventId, action, outcome, reversible, rollbackToken}` |
| `security` | `SECURITY_NOTIFICATION` | `{messageId, severity, ...os 8 campos de explicação}` |
| `security` | `SECURITY_INCIDENT_UPDATED` | `{incidentId, count, lastSeen, severity}` |
| `security` | `CIRCUIT_BREAKER_OPENED` | `{subject, reason, evidence}` |
| `security` | `CIRCUIT_BREAKER_CLOSED` | `{subject, by}` |
| `security` | `LOCKDOWN_ENTERED` | `{reason, trigger, auto}` |
| `security` | `LOCKDOWN_EXITED` | `{by}` — sempre `user` |
| `security` | `QUARANTINE_ADDED` | `{vaultId, originalPath, reason, restorable}` |
| `security` | `QUARANTINE_RESTORED` | `{vaultId, by}` |
| `change` | `CHANGE_PLANNED` | `{taskId, scope, repository, files, specs, risk, touchesTrustKernel}` |
| `change` | `CHANGE_APPROVED` | `{taskId, by, decision}` |
| `change` | `CHANGE_APPLIED` | `{taskId, branch, commits, filesChanged}` |
| `change` | `CHANGE_REVERTED` | `{taskId, by, revertCommit}` |
| `change` | `TRUST_KERNEL_PROPOSAL` | `{taskId, files, prUrl}` — proposta, nunca aplicação |

**Os tópicos `security` e `change` não podem ser desassinados.** `session.unsubscribe` o rejeita
com `ERR_INVALID_ARGUMENT`: um cliente que recusasse eventos de segurança
quebraria a invariante de [Comunicação §1](../security/communication.md#1-o-princípio-fundamental).

Clientes assinam tópicos, não eventos individuais. A tela de Chat assina
`chat,voice,permission`; a tela de Diagnostics assina tudo. Isso evita mandar
`SYSTEM_METRICS` a 1 Hz para uma UI que está mostrando outra coisa.

### Política de fila por tópico

| Tópico | Política quando a fila enche |
|---|---|
| `permission` | **Nunca descarta.** Fila dedicada, sem limite prático |
| `security` | **Nunca descarta.** Fila dedicada + espelhada em fila durável ([Comunicação §8](../security/communication.md#8-entrega-garantida)) |
| `change` | **Nunca descarta.** Alteração de projeto não pode passar despercebida |
| `chat`, `agents`, `tools` | `DROP_OLDEST` com marcador `gap: true` no próximo evento |
| `system` (métricas), `voice` (`VOICE_LEVEL`) | `COALESCE` — substitui o pendente do mesmo recurso |
| demais | `DROP_OLDEST` |

Publicar **nunca** bloqueia o produtor. Ver
[ADR-0011](../adr/ADR-0011-event-bus-com-replay.md).

## 7. Frames binários

Áudio e imagem não viajam em JSON. Frames WebSocket binários com cabeçalho fixo
de 8 bytes, big-endian:

```text
 0        1        2        3        4        5        6        7        8
 ┌────────┬────────┬─────────────────┬───────────────────────────────────┐
 │ 0x5A   │ type   │   streamId u16  │            seq u32                │
 └────────┴────────┴─────────────────┴───────────────────────────────────┘
 │ 'Z'    │        │                 │                                   │
 └────────────────────── payload ────────────────────────────────────────┘
```

| `type` | Significado | Payload |
|---|---|---|
| `0x01` | `AUDIO_IN` — host → núcleo | PCM 16 kHz, mono, s16le, 20 ms (640 bytes) |
| `0x02` | `AUDIO_OUT` — núcleo → host | PCM 22,05 kHz, mono, s16le, ou Opus se negociado |
| `0x03` | `AUDIO_END` | vazio — fim do stream |
| `0x10` | `IMAGE` — host → núcleo | PNG ou JPEG completo |
| `0x20` | `FILE_CHUNK` | bytes opacos |

O byte mágico `0x5A` ('Z') existe para falhar alto se um frame binário chegar
onde não devia.

Um stream binário é sempre **anunciado por um método JSON antes** (`audio.play`
devolve o `streamId` que os frames vão usar). Frames com `streamId` desconhecido
são descartados e geram `SYSTEM_ALERT`.

### Controle de fluxo

Áudio de entrada é contínuo e não pode acumular. O núcleo concede crédito:

- No `session.hello`, o núcleo informa `audioCreditFrames` (padrão 50 = 1 s).
- O host pode ter no máximo esse número de frames não confirmados.
- O núcleo envia `audio.credit {streamId, frames}` conforme consome.
- Se o host estourar o crédito, ele **descarta os mais antigos**, não bufferiza.
  Áudio velho não tem valor; latência tem.

Áudio de saída (TTS) usa o caminho inverso, com o host concedendo crédito
conforme a fila da placa de som esvazia.

## 8. Reconexão e replay

O núcleo mantém um anel dos últimos **2.000 eventos** ou **5 minutos**, o que
for menor. Ao reconectar, o cliente envia em `session.hello`:

```json
"resume": { "startId": "01J9X2K7QF8ZP3", "lastEventSeq": 48210 }
```

| Situação | Comportamento |
|---|---|
| `startId` igual e `seq` dentro do anel | `resumed: true`, reenvia de `48211` em diante |
| `startId` igual e `seq` velho demais | `resumed: false`, cliente faz snapshot completo |
| `startId` diferente | O núcleo reiniciou: `resumed: false` + `CORE_STARTED` |

O cliente **nunca** assume continuidade: ao receber `resumed: false`, ele
descarta estado volátil e recarrega via `chat.history`, `system.metrics`,
`mcp.list`, `agent.list`.

Backoff de reconexão: 250 ms, 500 ms, 1 s, 2 s, 5 s, 10 s (teto), com jitter de
±20%. Em paralelo, o cliente observa `endpoint.json` — se ele mudar, tenta
imediatamente com o novo endereço e token, sem esperar o backoff.

## 9. Erros

Códigos JSON-RPC padrão (`-32700` a `-32603`) para erros de protocolo. Erros de
aplicação usam `-32001` com `data.kind` carregando o significado:

| `data.kind` | Significado | Ação do cliente |
|---|---|---|
| `ERR_UNAUTHORIZED` | Token inválido ou ausente | Reler `endpoint.json` e reconectar |
| `ERR_PROTOCOL_UNSUPPORTED` | Versão incompatível | Avisar e pedir atualização |
| `ERR_BRIDGE_UNAVAILABLE` | Nenhum host conectado | Degradar a funcionalidade |
| `ERR_PERMISSION_DENIED` | Usuário ou política negou | Exibir; não repetir automaticamente |
| `ERR_BUDGET_EXCEEDED` | Teto de tokens/tempo/passos | Exibir o parcial obtido |
| `ERR_TOOL_FAILED` | Ferramenta falhou | Já tratado pelo agente; informativo |
| `ERR_MCP_UNAVAILABLE` | Servidor MCP fora | Mostrar no painel de MCP |
| `ERR_AI_UNAVAILABLE` | Provider fora ou sem cota | Oferecer fallback local |
| `ERR_RATE_LIMITED` | Limite interno | Respeitar `data.retryAfterMs` |
| `ERR_INVALID_ARGUMENT` | Falha de validação | Erro de programação; logar |
| `ERR_NOT_FOUND` | Recurso inexistente | |
| `ERR_CANCELLED` | Cancelado | Não é erro para o usuário |
| `ERR_LOCKDOWN` | Sistema em Defense Lockdown | Exibir estado; só o usuário sai |
| `ERR_CIRCUIT_OPEN` | Disjuntor aberto para o sujeito | Exibir motivo e evidências |
| `ERR_IMMUTABLE` | Tentativa de alterar política, auditoria ou capacidade | **Nunca acontece em uso normal** — gera `SECURITY_FINDING` CRITICAL |

Códigos de fechamento de WebSocket:

| Código | Significado |
|---|---|
| `4400` | Protocolo incompatível |
| `4401` | Não autorizado |
| `4403` | `Origin` presente — provável navegador |
| `4408` | Heartbeat perdido |
| `4429` | Excesso de mensagens |
| `4500` | Núcleo encerrando |

## 10. Heartbeat

O núcleo envia ping WebSocket a cada `heartbeatIntervalMs` (padrão 10 s). O
cliente responde pong. Três pings sem pong → fechamento com `4408`. Do outro
lado, o cliente que passa 3 intervalos sem qualquer tráfego marca o núcleo como
offline e inicia reconexão. Ping/pong de WebSocket é usado em vez de mensagem de
aplicação porque detecta socket meio-aberto, que é o modo de falha real quando a
VM do WSL é suspensa.

## 11. Compatibilidade e evolução

- **Adicionar** campo opcional, método ou tipo de evento: não quebra. Clientes
  ignoram o que não conhecem — isso é requisito, não gentileza.
- **Remover ou mudar significado**: exige `/zwp/v2`. O núcleo pode servir v1 e
  v2 simultaneamente durante a transição.
- Todo evento novo nasce em um tópico existente ou traz o seu; clientes antigos
  não assinam o que não conhecem.
- O `zordon-api` é a fonte da verdade: os records Java geram o JSON, e há teste
  de contrato com amostras douradas (`golden files`) versionadas garantindo que
  a serialização não muda por acidente.
