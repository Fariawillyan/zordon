---
document: spec-009
module: voice
section: spec
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [spec,voz,zwp,frames,binario,credito,microfone,nivel]
specId: SPEC-009
---

# SPEC-009 — Frames de áudio e teste do microfone

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `CoreAgent` (frames e nível), `HostAgent` (envio), `JavaFxAgent` (teste) |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M2 |
| **Supera** | — |

Aprovada em 2026-09-18 pelo "continue" do owner para o próximo item do M2
(frames binários), seguindo o formato já documentado em
[ZWP §7](../../api/zwp-protocol.md#7-frames-binários).

## 1. Objetivo

O áudio capturado no Windows chegar ao núcleo em frames binários, sem acumular
atraso, e o usuário poder testar o microfone vendo o nível do sinal que o núcleo
de fato recebe.

## 2. Problema

O host captura e descarta ([SPEC-007 §4](../host/SPEC-007-host-do-windows.md#4-não-escopo)):
nada liga o microfone ao núcleo. Sem isso o sidecar de voz não tem o que ouvir,
e a tela de Voz não pode oferecer o teste do microfone, adiado desde a
[SPEC-006](SPEC-006-tela-e-estado-da-voz.md). O formato dos frames e o controle
de fluxo estão documentados, mas não implementados.

## 3. Escopo

- **Codec** de frame binário em `zordon-zwp`, com o tipo em `zordon-api`: cabeçalho
  de 8 bytes big-endian (`0x5A`, tipo, `streamId` u16, `seq` u32) e payload.
- **Transporte**: cliente envia e recebe frames binários; o servidor os entrega
  ao dono do stream na sessão que o anunciou.
- **Stream anunciado por JSON**: ao pedir captura, o núcleo aloca o `streamId` e
  o manda em `audio.setCaptureEnabled {enabled: true, streamId}`. O host envia
  `AUDIO_IN` com ele e `AUDIO_END` ao desligar.
- **Crédito**: `session.hello` devolve `audioCreditFrames` (50 = 1 s). O host
  nunca tem mais frames não confirmados que o crédito; sem crédito, descarta o
  frame e conta. O núcleo devolve crédito com `audio.credit {streamId, frames}`
  (notificação) conforme consome.
- **Nível no núcleo**: cada frame vira RMS e pico em dBFS; o núcleo publica
  `VOICE_LEVEL {rms, peak}` a no máximo 20 Hz, e só durante o teste ou uma escuta
  ativa. Com o microfone ligado o tempo todo no modo `wake`, 20 eventos por
  segundo expulsariam a conversa do anel de replay (2.000 eventos ≈ 100 s). Os
  frames são descartados depois do cálculo até existir o sidecar.
- **Teste do microfone**: `voice.testMicrophone {seconds}` (1 a 10, padrão 5)
  liga a captura pelo tempo pedido, mesmo sem motor, e desliga sozinho. O
  snapshot ganha `test {until}` durante o teste e `lastTest {peakDbfs,
  averageDbfs, verdict}` depois.
- **Desktop**: cartão "Teste do microfone" junto aos modos, com botão, medidor ao
  vivo e o veredito; cabeçalho "Testando microfone…" durante o teste.

## 4. Não escopo

- `AUDIO_OUT` (fala do Zordon pelo host), `IMAGE`, `FILE_CHUNK`: chegam com o
  sidecar e com o M3.
- Encaminhar os frames ao sidecar e o pré-roll de 1,5 s: SPEC do sidecar.
- Porta de ruído no host e calibração do piso: dependem de medir o ambiente por
  mais tempo; o veredito do teste só distingue sem sinal, baixo e bom.
- Opus: o áudio vai em PCM; 32 KB/s em loopback é irrelevante
  ([R6](../../architecture/windows-wsl.md#r6--não-há-áudio-confiável-dentro-do-wsl)).

## 5. Arquitetura

```text
 host (Windows)                               núcleo (WSL)
 ──────────────                               ────────────
 Microphone ─► StreamingSink ── AUDIO_IN ──►  ZwpServer ─► AudioIngest(streamId)
                 │  crédito ≤ 50               (binário)      │ RMS / pico
                 │◄──── audio.credit ─────────────────────────┤
                 │                                            ├─► VOICE_LEVEL ≤ 20 Hz
                 └── AUDIO_END ao desligar                    └─► teste: pico e média
```

Regra de privacidade da SPEC-006 §5, ampliada: a captura só é ligada quando há
quem consuma o áudio **ou** quando o usuário pede o teste, que é visível
("Testando microfone…") e acaba sozinho em no máximo 10 s.

Um frame só é aceito se vier da sessão do host ativo, com o `streamId` do pedido
de captura em vigor. Qualquer outro é descartado e conta para um `SYSTEM_ALERT`,
limitado a um por sessão por minuto.

## 6. Fluxo

1. Hello → `audioCreditFrames: 50`.
2. Usuário aperta "Testar microfone" → `voice.testMicrophone {seconds: 5}` →
   núcleo aloca `streamId`, pede `audio.setCaptureEnabled {enabled: true,
   streamId}` → host confirma → snapshot com `test.until`.
3. Host envia `AUDIO_IN` a cada 20 ms enquanto houver crédito.
4. Núcleo calcula nível, publica `VOICE_LEVEL`, devolve crédito a cada 10 frames.
5. Prazo vence → núcleo pede `enabled: false` → host envia `AUDIO_END` e
   confirma → snapshot com `lastTest`.

## 7. Interfaces

| Método / frame | Direção | Formato |
|---|---|---|
| `session.hello` | núcleo → cliente | resultado ganha `audioCreditFrames` |
| `audio.setCaptureEnabled` | núcleo → host | params ganham `streamId` quando `enabled: true` |
| `audio.credit` | núcleo → host | notificação `{streamId, frames}` |
| `voice.testMicrophone` | cliente → núcleo | `{seconds?}` → snapshot; sem host, `ERR_BRIDGE_UNAVAILABLE` |
| `AUDIO_IN` (`0x01`) | host → núcleo | PCM 16 kHz mono s16le, 640 bytes |
| `AUDIO_END` (`0x03`) | host → núcleo | vazio |
| `VOICE_LEVEL` | evento `voice` | `{rms, peak}` em dBFS, de -90 a 0 |

Veredito do teste (média do RMS dos frames com sinal):

| Condição | `verdict` |
|---|---|
| Pico abaixo de -60 dBFS | `silent` — "nenhum sinal: microfone mudo ou errado" |
| Média abaixo de -45 dBFS | `low` — "sinal baixo: aproxime-se ou aumente o ganho" |
| Caso contrário | `ok` — "sinal bom" |

Java: `BinaryFrame(type, streamId, seq, payload)` e `FrameType` em `zordon-api`;
`BinaryFrameCodec` em `zordon-zwp`; `ZwpClient.sendBinary`, `onBinary`;
`ZwpServer.onBinary(handler)`; `AudioIngest` e `AudioLevel` no núcleo;
`StreamingSink` no host.

## 8. Eventos

| Tópico | Evento | Payload |
|---|---|---|
| `voice` | `VOICE_LEVEL` | `{rms, peak}` — sem áudio, só dois números |
| `system` | `SYSTEM_ALERT` | frame descartado: `{message, sessionId, streamId}` |

## 9. Dados

Nenhum. Frames vivem só na memória do caminho host → núcleo e são descartados
depois do cálculo de nível. O resultado do último teste vive no `VoiceService`
até o núcleo reiniciar.

## 10. Segurança

- Frame de sessão que não é o host ativo, ou de stream não anunciado, é
  descartado: nenhum cliente injeta áudio no pipeline de voz.
- O teste é pedido pelo usuário, tem teto de 10 s e aparece no cabeçalho; não há
  captura silenciosa.
- `VOICE_LEVEL` carrega dois números; nenhum byte de áudio vai para evento, log
  ou disco.
- O crédito impede que um núcleo lento acumule áudio antigo no host.

## 11. Permissões

O teste do microfone é ação do usuário na própria máquina. Nenhum agente chama
`voice.testMicrophone`.

## 12. Observabilidade

- INFO: início e fim do teste, com o veredito; stream aberto e fechado.
- DEBUG: frames recebidos, crédito devolvido, frames descartados por falta de
  crédito (contados no host).
- `SYSTEM_ALERT` para frame recusado.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Teste sem host | `ERR_BRIDGE_UNAVAILABLE` |
| Host não liga o microfone | Teste termina na hora; `lastTest.verdict: failed` com o motivo do host |
| `seconds` fora de 1 a 10 | `ERR_INVALID_ARGUMENT` |
| Teste pedido com teste em andamento | Estende até o novo prazo, sem passar de 10 s do pedido |
| Frame com magia errada ou cabeçalho curto | Descartado, alerta |
| Host sem crédito | Descarta o frame novo e conta; não bufferiza |
| Host cai no meio do teste | Teste termina; `verdict: failed`, "host desconectou" |

## 14. Testes

- Codec: ida e volta, magia errada, cabeçalho curto, limites de `u16` e `u32`.
- Crédito no host com conexão falsa: nunca passa de 50 pendentes; descarta sem
  crédito; volta a enviar com crédito.
- Núcleo: `AudioIngest` com frames sintéticos (silêncio, seno a -20 dBFS): nível,
  taxa de `VOICE_LEVEL`, crédito devolvido, veredito.
- Ponta a ponta: host de teste por WebSocket envia frames reais; frame de stream
  desconhecido é recusado com alerta.
- Desktop: regras do cartão e do cabeçalho sem toolkit; captura de tela do cartão.
- Verificação real: o usuário aperta "Testar microfone" no desktop do Windows e
  fala; o medidor sobe e o veredito aparece.

### Evidências (2026-09-18)

- `AudioFramesTest`, com o núcleo inteiro e um host de teste por WebSocket:
  frames reais de um seno a -20 dBFS viraram `VOICE_LEVEL` no desktop (no máximo
  25 em ~1,2 s), o host recebeu `audio.credit` de 10 em 10 frames, o teste acabou
  sozinho com `verdict: ok` e a captura desligou; um frame de stream não anunciado
  gerou `SYSTEM_ALERT`.
- Núcleo, host e desktop reinstalados no serviço e no Windows; o host novo
  conectou e confirmou a captura desligada; o desktop do Windows conectou.
- Teste real aprovado pelo owner em 2026-09-18, falando no microfone do Windows
  pelo desktop ("sim a voz funcionou").

## 15. Critérios de aceite

- `CA-1` Dado um frame, então o codec o escreve com cabeçalho de 8 bytes
  big-endian começando em `0x5A` e o lê de volta igual; magia errada ou
  cabeçalho curto são recusados.
- `CA-2` Dado o `session.hello`, então o resultado traz `audioCreditFrames: 50`.
- `CA-3` Dado um pedido de captura, então o núcleo manda um `streamId` novo e o
  host envia `AUDIO_IN` com ele e `seq` crescente, e `AUDIO_END` ao desligar.
- `CA-4` Dado o host capturando, então ele nunca tem mais frames não confirmados
  que o crédito; sem crédito descarta o frame em vez de guardar; com
  `audio.credit` volta a enviar.
- `CA-5` Dado um frame de stream não anunciado ou de sessão que não é o host
  ativo, então ele é descartado e gera no máximo um `SYSTEM_ALERT` por sessão
  por minuto.
- `CA-6` Dados frames aceitos, então o núcleo publica `VOICE_LEVEL` com RMS e
  pico em dBFS a no máximo 20 Hz, só durante o teste ou escuta ativa, e devolve
  crédito conforme consome; nenhum byte de áudio aparece em evento.
- `CA-7` Dado `voice.testMicrophone`, então a captura liga mesmo sem motor pelo
  tempo pedido (1 a 10 s), desliga sozinha no prazo e o snapshot traz `test.until`
  durante e `lastTest` com pico, média e veredito depois; sem host, falha com
  `ERR_BRIDGE_UNAVAILABLE`.
- `CA-8` Dado o teste, então o cabeçalho mostra "Testando microfone…", o cartão
  mostra o medidor ao vivo e, ao fim, o veredito; sem host o botão fica
  desabilitado com o motivo.

## 16. Impacto em outros módulos

- `zordon-api`: `BinaryFrame`, `FrameType`; `HelloResult.audioCreditFrames`;
  eventos `VOICE_LEVEL`.
- `zordon-zwp`: codec binário; envio e recepção no cliente.
- `zordon-core`: recepção no servidor, `AudioIngest`, `AudioLevel`, teste no
  `VoiceService`, `voice.testMicrophone`.
- `zordon-host`: `StreamingSink` com crédito; `AUDIO_END`.
- `zordon-desktop`: cartão de teste, medidor, cabeçalho.
- Documentação: [ZWP §4, §6 e §7](../../api/zwp-protocol.md), a regra ampliada
  na [SPEC-006 §5](SPEC-006-tela-e-estado-da-voz.md#5-arquitetura).

## 17. Dependências

- [SPEC-006](SPEC-006-tela-e-estado-da-voz.md) · [SPEC-007](../host/SPEC-007-host-do-windows.md)
- [ZWP §7](../../api/zwp-protocol.md#7-frames-binários) · [Voz — pipeline](design.md)
