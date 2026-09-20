---
document: spec-006
module: voice
section: spec
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [spec,voz,desktop,zwp,host,privacidade,microfone]
specId: SPEC-006
---

# SPEC-006 — Tela de Voz e estado da voz

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `JavaFxAgent` (tela), `CoreAgent` (estado e ZWP) |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M2 |
| **Supera** | — |

## 1. Objetivo

O usuário escolher o modo da voz e saber, a qualquer momento e sem ambiguidade,
se o microfone está capturando — com o estado vindo de quem controla o
microfone, não de uma suposição da interface.

## 2. Problema

A [revisão de design](../ui/design-review.md#3-cobertura-dos-contratos) registra
a lacuna da voz: não há estado completo, confirmação de captura desligada,
seleção de dispositivo nem prazo do modo `open`. O cabeçalho do desktop mostra
"Voz indisponível" fixo ([SPEC-005](../ui/SPEC-005-shell-do-desktop.md)).

O host do Windows e o sidecar `zordon-voice` ainda não existem. Se a tela
nascer antes deles sem contrato, ela vai inventar estado; se esperar por eles,
o host nasce sem saber o que precisa informar. Esta SPEC fixa o contrato e
entrega a tela sobre ele, com host e motor de voz simulados nos testes.

## 3. Escopo

- **Núcleo → cliente no ZWP**: o núcleo passa a requisitar métodos a um cliente
  ([ZWP §5](../../api/zwp-protocol.md#5-métodos--núcleo--cliente)), com prazo,
  correlação por sessão e falha imediata quando não há cliente capaz.
- **Cliente ZWP atende requisições**: `zordon-zwp` registra tratadores para
  métodos vindos do núcleo; método desconhecido responde erro.
- **Capacidades do cliente**: o núcleo guarda as `capabilities` do
  `session.hello`. Só um cliente `host` que declarou `audio.capture` controla o
  microfone.
- **`VoiceService` no núcleo**: modo desejado persistido, estado efetivo
  derivado de host e motor, sincronização da captura com o host, prazo do modo
  `open`.
- **Contrato do motor de voz** (`VoiceEngine`): estado (`absent`, `starting`,
  `ready`, `failed`) e atividade (`idle`, `listening`, `thinking`, `speaking`).
  Nesta SPEC a implementação de produção é `AbsentVoiceEngine`; o sidecar a
  substitui depois.
- **ZWP**: `voice.status`, `voice.setMode` com retorno completo, `voice.devices`,
  `voice.selectDevice`; evento `VOICE_STATE`; métodos de host
  `audio.setCaptureEnabled` (já documentado), `audio.listDevices` e
  `audio.selectDevice`.
- **Desktop**: destino Voz disponível; tela de Voz; rótulo de voz do cabeçalho
  derivado do estado; acesso explícito a desligar o microfone no cabeçalho
  ([Layout §5](../ui/desktop-layout.md#5-inspector-e-cabeçalho)).

## 4. Não escopo

- `zordon-host` (captura, reprodução, hotkey, autostart): SPEC própria, que
  implementa os métodos de host definidos aqui.
- Sidecar `zordon-voice` (VAD, wake word, STT, TTS) e frames binários: SPEC
  própria, que implementa `VoiceEngine`.
- Teste de microfone, medidor de nível e calibração do piso de ruído: dependem
  de captura real e entram com o host. A tela mostra a seção indisponível, com o
  motivo.
- Botão de voz no composer, `voice.startListening` por UI e barge-in: entram com
  o sidecar.
- Overlay e ícone do tray: o tray não existe no WSLg; o overlay tem item próprio
  no [roadmap](../../roadmap.md#m2--voz).
- Seleção de voz do TTS: configuração do sidecar.

## 5. Arquitetura

```text
 desktop                         núcleo                               host (Windows)
 ───────                         ──────                               ──────────────
 VoiceView ── voice.setMode ──►  VoiceService ── audio.setCapture ──►  microfone
   ▲          voice.status        │  ▲   Enabled {enabled}               │
   │                              │  │                                   │
   └──── VOICE_STATE ◄────────────┘  └── {enabled} (confirmação) ◄──────┘
                                  │
                                  └── VoiceEngine (AbsentVoiceEngine | sidecar)
```

Regra central, de [privacidade](design.md#6-privacidade-e-falsos-positivos):
**a captura só é ligada quando há quem consuma o áudio.** O núcleo pede
`enabled: true` ao host apenas se o modo efetivo for `wake`, `push` ou `open` e o
motor estiver `ready`. Com o motor ausente, a captura é mantida desligada mesmo
que o modo desejado seja `wake`. A única outra exceção é o teste do microfone
pedido pelo usuário, visível e de no máximo 10 s
([SPEC-009](SPEC-009-frames-de-audio-e-teste-do-microfone.md#5-arquitetura)).

Estado efetivo:

| Modo desejado | Host | Motor | Efetivo | Captura pedida |
|---|---|---|---|---|
| `off` | qualquer | qualquer | `off` | desligada |
| `wake`/`push`/`open` | ausente | qualquer | `unavailable` — "host do Windows não conectado" | — |
| `wake`/`push`/`open` | conectado | não `ready` | `unavailable` — motivo do motor | desligada |
| `wake`/`push`/`open` | conectado | `ready` | o modo desejado | ligada |

`open` é temporário: dura 5 min a partir do pedido e volta ao modo persistido
anterior. Contar do pedido, e não de quando ele se torna efetivo, evita que o
microfone abra minutos depois, quando o usuário já esqueceu que pediu. `open`
nunca é persistido.

Estado da captura, do ponto de vista do núcleo:

| `capture.state` | Significado |
|---|---|
| `on` | O host confirmou `enabled: true` |
| `off` | O host confirmou `enabled: false` |
| `pending` | Pedido enviado, sem resposta ainda |
| `unknown` | Sem host, ou o host não respondeu no prazo |

`capture.requested` diz o que foi pedido por último (`true`, `false` ou `null`),
para a interface distinguir "Ligando microfone…" de "Desligando microfone…".

O núcleo só afirma `off` com confirmação do host. É a diferença entre "não
estamos ouvindo" e "achamos que não estamos ouvindo".

## 6. Fluxo

1. Host conecta com `kind: host` e `audio.capture` → `VoiceService` pede a
   captura correspondente ao estado efetivo, aplica o dispositivo preferido
   (se houver) e publica `VOICE_STATE`.
2. Usuário escolhe `wake` na tela → `voice.setMode` persiste o modo, recalcula o
   efetivo, pede a captura se mudou e responde o snapshot.
3. Host responde `{enabled}` → `capture` passa a `on`/`off` com `confirmedAt`;
   sem resposta em 2 s → `unknown`, `VOICE_STATE` e aviso na tela.
4. Host desconecta → `host.connected: false`, `capture: unknown`, efetivo
   `unavailable`. Na reconexão, o passo 1 se repete.
5. Motor muda de estado ou de atividade → recalcula e publica.
6. Prazo do `open` vence → volta ao modo anterior e publica.

## 7. Interfaces

Cliente → núcleo:

| Método | Params | Retorno |
|---|---|---|
| `voice.status` | `{}` | snapshot (abaixo) |
| `voice.setMode` | `{mode: "off"\|"wake"\|"push"\|"open"}` | snapshot |
| `voice.devices` | `{}` | `{devices[{id, name, default}], selected}` |
| `voice.selectDevice` | `{deviceId}` | snapshot |

Snapshot:

```json
{
  "mode": "wake",
  "effective": "unavailable",
  "reason": "motor de voz não instalado",
  "activity": "idle",
  "capture": { "state": "off", "requested": false, "confirmedAt": "2026-09-18T17:02:11Z" },
  "host": { "connected": true, "device": "Microfone (USB Audio)" },
  "engine": { "state": "absent", "reason": "motor de voz não instalado" }
}
```

Campo sem valor (`reason`, `openUntil`, `capture.requested`,
`capture.confirmedAt`, `host.device`, `engine.reason`) é omitido: o protocolo não
carrega `null`. No exemplo, `openUntil` apareceria só com o modo `open`.

Núcleo → host (prazo de [ZWP §5](../../api/zwp-protocol.md#5-métodos--núcleo--cliente)):

| Método | Params | Retorno | Prazo |
|---|---|---|---|
| `audio.setCaptureEnabled` | `{enabled}` | `{enabled}` | 2 s |
| `audio.listDevices` | `{}` | `{devices[{id, name, default}], selected}` | 5 s |
| `audio.selectDevice` | `{deviceId}` | `{selected, name}` | 5 s |

Java:

- `ClientRequests` (núcleo): `CompletableFuture<Map<String,Object>> request(sessionId, method, params, timeout)`,
  implementado pelo `ZwpServer`.
- `AsyncMethodHandler` (núcleo): método servido que responde depois, sem prender
  a thread do WebSocket enquanto espera outro cliente.
- `ZwpClient.handle(method, handler)` e `CoreConnection.handle(method, handler)` (`zordon-zwp`).
- `VoiceEngine`: `state()`, `activity()`, `onChange(listener)`.
- `VoiceService`: `status()`, `setMode(mode)`, `devices()`, `selectDevice(id)`.

## 8. Eventos

| Tópico | Evento | Payload | Política |
|---|---|---|---|
| `voice` | `VOICE_STATE` | snapshot | A do tópico `voice`. A cada conexão, retomada ou não, o cliente pede `voice.status`; o mesmo valerá para `gap` quando o envelope tiver o marcador |

Os eventos `VOICE_STARTED`, `VOICE_PARTIAL`, `VOICE_STOPPED`, `VOICE_LEVEL` e
`TTS_*` de [ZWP §6](../../api/zwp-protocol.md#catálogo) não mudam. A tela lista
os `VOICE_STOPPED` recebidos na sessão; quem os publica é o sidecar.

## 9. Dados

`~/.zordon/state/voice.json`, permissão `0600`:

```json
{ "mode": "wake", "deviceId": "..." }
```

Só o modo persistível (`off`, `wake`, `push`) e o dispositivo. Nenhum áudio,
transcrição ou nível. Arquivo ilegível → modo `off` e aviso no log: na dúvida, o
microfone fica desligado.

Modo padrão sem arquivo: `off`. O [design da voz](design.md#3-máquina-de-estados)
diz que `wake` é o padrão; ele passa a valer quando houver motor. Ligar o
microfone é escolha do usuário na primeira vez.

## 10. Segurança

- Captura ligada só com consumidor (§5). Sem motor, nenhum frame é capturado.
- `off` só é afirmado com confirmação do host (§5).
- Só `kind: host` com `audio.capture` recebe métodos de áudio. Um `desktop` ou
  `test` que declare a capacidade é ignorado para isso
  ([ZWP §2](../../api/zwp-protocol.md#sessionhello)).
- Resposta a uma requisição do núcleo só vale se vier da mesma sessão, com o
  mesmo `id`. Resposta atrasada, depois do prazo, é descartada.
- O snapshot não carrega áudio, transcrição nem texto parcial.
- Nome de dispositivo é dado local; aparece em logs em nível DEBUG apenas.

## 11. Permissões

Mudar modo e dispositivo é ação do usuário na própria máquina, sem classificação
de risco. Nenhum agente chama `voice.setMode` nesta SPEC.

## 12. Observabilidade

- Log INFO a cada mudança de efetivo ou de captura: modo, efetivo, captura,
  motivo.
- Log WARN quando o host não confirma a captura no prazo.
- `system.diagnostics` ganha `voice` com o snapshot.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| `voice.setMode` com modo inválido | `ERR_INVALID_ARGUMENT` |
| `voice.devices` / `selectDevice` sem host | `ERR_BRIDGE_UNAVAILABLE` |
| Host não responde no prazo | `capture: unknown`; WARN; tela mostra "Captura não confirmada pelo host" |
| Host responde `{enabled}` diferente do pedido | Estado reflete o que o host disse; efetivo `unavailable` com motivo |
| Dois hosts conectados | O mais recente controla; o anterior recebe `enabled: false` |
| `voice.json` corrompido | Modo `off`, WARN, arquivo reescrito na próxima mudança |
| Cliente sem tratador para o método pedido | Responde método desconhecido (`-32601`), como o servidor; núcleo trata como indisponível |

## 14. Testes

- Núcleo: `VoiceService` com relógio e motor falsos; ZWP de ponta a ponta com um
  cliente `host` de teste que responde, atrasa ou não responde.
- `zordon-zwp`: tratador de requisição do núcleo, método desconhecido.
- Desktop: regras puras (rótulo do cabeçalho, seções da tela) sem toolkit; tela
  montada sob display, como na [SPEC-005](../ui/SPEC-005-shell-do-desktop.md).

## 15. Critérios de aceite

> **Onde o estado aparece mudou com a [SPEC-010](../ui/SPEC-010-shell-compacto-centrado-na-voz.md)**
> (2026-09-18): não há mais cabeçalho. Os rótulos de `CA-12` e as garantias de
> `CA-14` passaram à pílula no alto do console, que só some em repouso; a tela de
> `CA-13` passou aos Ajustes. As regras continuam as mesmas.

- `CA-1` Dado o núcleo no ar, então `voice.status` devolve modo, efetivo, motivo,
  atividade, prazo do `open`, captura, host e motor, sem áudio nem transcrição.
- `CA-2` Dado nenhum host conectado, quando o usuário escolhe `wake`, então o modo
  é persistido, o efetivo é `unavailable` com o motivo "host do Windows não
  conectado" e, após reiniciar o núcleo, o modo continua `wake`.
- `CA-3` Dado um host conectado e o motor ausente, então o núcleo pede
  `enabled: false`, e a captura fica `off` com o horário da confirmação.
- `CA-4` Dado host e motor prontos, quando o modo é `wake`, então o núcleo pede
  `enabled: true`, e só depois da confirmação a captura fica `on`.
- `CA-5` Dada a captura `on`, quando o usuário escolhe `off`, então a captura fica
  `pending` até o host confirmar; sem confirmação em 2 s, fica `unknown`. Em
  nenhum momento antes da confirmação o estado é `off`.
- `CA-6` Dado o host desconectado, então a captura é `unknown` e o efetivo
  `unavailable`; quando ele reconecta, o núcleo volta a pedir a captura do
  estado efetivo e reaplica o dispositivo preferido.
- `CA-7` Dado o pedido do modo `open`, então `openUntil` é 5 min depois do pedido;
  vencido o prazo, o modo volta ao anterior, e `open` nunca é persistido.
- `CA-8` Dado um host, então `voice.devices` lista os dispositivos dele e
  `voice.selectDevice` persiste a escolha; sem host, ambos falham com
  `ERR_BRIDGE_UNAVAILABLE`.
- `CA-9` Dado um cliente `desktop` que declara `audio.capture`, então ele nunca
  recebe `audio.setCaptureEnabled`.
- `CA-10` Dada uma requisição do núcleo, então resposta de outra sessão ou depois
  do prazo é descartada, e requisição sem cliente capaz falha na hora com
  `ERR_BRIDGE_UNAVAILABLE`.
- `CA-11` Dado um cliente com tratador registrado, então ele responde à
  requisição do núcleo; método sem tratador responde método desconhecido.
- `CA-12` Dado o snapshot, então o cabeçalho mostra exatamente um de: "Voz
  desligada", "Aguardando “Zordon”", "Voz por atalho", "Ouvindo comando",
  "Conversa aberta até HH:MM", "Voz indisponível", "Ligando microfone…",
  "Desligando microfone…", "Captura não confirmada"; e, com a captura `on`,
  oferece "Desligar microfone".
- `CA-13` Dada a tela de Voz, então ela mostra modo desejado e efetivo separados,
  o motivo quando indisponível, o estado da captura com o horário da
  confirmação, host e dispositivo, e a seção de teste indisponível com o motivo.
- `CA-14` Dada a captura `pending` ou `unknown` com um host conectado, então a
  tela e o cabeçalho nunca dizem "desligado": dizem "Desligando microfone…",
  "Ligando microfone…" ou "Captura não confirmada".

## 16. Impacto em outros módulos

- `zordon-api`: eventos `VOICE_STATE` e `VOICE_STOPPED` no catálogo tipado.
- `zordon-zwp`: tratadores de requisições do núcleo no cliente.
- `zordon-core`: `ZwpSession` guarda as capacidades do hello; `ClientRequests`,
  `AsyncMethodHandler`, `VoiceService`, `VoiceStore`, `VoiceEngine`,
  `AbsentVoiceEngine`, `VoiceMethods`; `system.diagnostics` ganha `voice`.
- `zordon-desktop`: destino Voz disponível, `VoiceView`, cabeçalho.
- Documentação: [ZWP §4, §5 e §6](../../api/zwp-protocol.md) com os métodos e o
  evento; [revisão de design](../ui/design-review.md#3-cobertura-dos-contratos)
  com a lacuna de voz fechada, exceto calibração.

## 17. Dependências

- [Voz — pipeline](design.md) · [ADR-0009](../../adr/ADR-0009-captura-windows-inferencia-wsl.md)
- [Layout desktop](../ui/desktop-layout.md) · [SPEC-005](../ui/SPEC-005-shell-do-desktop.md)
- [SPEC-002](../core/SPEC-002-fundacao-zwp-e-nucleo.md) — ZWP e sessão
