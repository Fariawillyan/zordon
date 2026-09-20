---
document: spec-011
module: voice
section: spec
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [spec,voz,sidecar,stt,tts,vad,whisper,piper,systemd]
specId: SPEC-011
---

# SPEC-011 — Motor de voz: ouvir e falar

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `VoiceAgent` (sidecar), `CoreAgent` (sessão de escuta), `HostAgent` (reprodução) |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M2 |
| **Supera** | — |

Aprovada em 2026-09-18 pelo owner ("prossiga para o M2 do motor de voz"), na
etapa 1 proposta: disparo por botão, sem palavra de ativação.

## 1. Objetivo

O usuário apertar a esfera do console, dizer "que horas são?" e ouvir a resposta
pela caixa de som do Windows — com tudo rodando na própria máquina.

## 2. Problema

O áudio chega ao núcleo ([SPEC-009](SPEC-009-frames-de-audio-e-teste-do-microfone.md))
e ninguém o entende: o `VoiceEngine` de produção é o `AbsentVoiceEngine`. Sem
transcrição não há comando, e sem síntese não há resposta falada.

Medido nesta máquina (sem GPU NVIDIA; 12 núcleos, 23 GB), com os modelos desta
SPEC:

| Etapa | Tempo |
|---|---|
| Piper, "São quinze horas e quarenta minutos." | 0,09 s |
| faster-whisper `small` int8, frase de ~1,2 s | 1,4 s (4,2 s na primeira, sem aquecer) |
| Carga dos modelos | 1,8 s |

## 3. Escopo

- **Sidecar `zordon-voice`** (Python, `voice/zordon_voice/`): servidor num socket
  Unix; VAD Silero v6 (o do faster-whisper) em fluxo contínuo; transcrição com
  faster-whisper `small` int8, `language="pt"`, `hotwords="Zordon"`; síntese com
  Piper, voz `pt_BR-faber-medium`; aquecimento ao iniciar.
- **Unit `zordon-voice.service`** ([ADR-0028](../../adr/ADR-0028-motor-de-voz-como-unit-systemd.md))
  e `packaging/wsl/install-voice.sh`: venv em `~/.zordon/venv` com dependências
  travadas por hash, modelos em `~/.zordon/models` baixados de revisão fixa e
  conferidos por SHA-256.
- **`SidecarVoiceEngine`** no núcleo: cliente do socket, estados `absent`,
  `starting`, `ready`, `failed`; reconexão com backoff.
- **Sessão de escuta**: `voice.startListening {reason: "ui"}` liga a captura por no
  máximo 15 s; o VAD encerra 0,6 s depois do fim da fala, ou sem fala em 6 s;
  `voice.stopListening` encerra na hora.
- **Comando por voz**: a transcrição vira um turno com `source: "voice"` numa
  conversa de voz; a resposta do turno (rota rápida ou modelo) chega ao
  **narrador** da [SPEC-012](SPEC-012-voice-first-narracao-e-estados.md), que a
  entrega ao motor para sintetizar e tocar pelo host. O motor é o `NarrationSink`
  de produção: nada é falado fora do narrador.
- **Reprodução no host**: `audio.play {streamId, format}` e frames `AUDIO_OUT`
  (PCM 22 050 Hz, mono, s16le), `AUDIO_END`, `audio.stop`; capacidade
  `audio.playback`.
- **Atividade**: `listening`, `thinking`, `speaking` no snapshot, que alimentam os
  estados visuais da SPEC-012 (sem texto na tela de Voz).
- **Desktop**: clicar na esfera, ou Ctrl+Espaço na janela, começa a escuta;
  clicar de novo encerra.
- **Rota rápida**: pontuação final ("Que horas são.") não impede o casamento.
- **Normalização mínima para a fala**: horários ("15h40" → "quinze horas e
  quarenta minutos", "21h" → "vinte e uma horas"). Medido: a voz já lê números
  ("8" → "oito"), mas lê "15h40" como "quinze agá quarenta". A normalização
  completa tem item próprio.

## 4. Não escopo

- Palavra de ativação "Zordon", máquina de estados completa, barge-in e modos
  `wake`/`open` contínuos: etapa 2.
- Hotkey global do Windows (modo `push` de verdade): com a etapa 2.
- Overlay: item próprio do roadmap.
- Responder perguntas gerais: depende do provider de IA, decidido no M3. Sem
  provider, o Zordon diz que não consegue responder.
- Crédito do áudio de saída: nesta etapa o núcleo envia o `AUDIO_OUT` no ritmo da
  reprodução, com 300 ms de folga; o crédito do host vem com o barge-in.

## 5. Arquitetura

```text
 desktop          núcleo (WSL)                               sidecar (WSL)
 ───────          ────────────                               ─────────────
 esfera ─ voice.startListening ─► VoiceService ─ listen ───► VAD Silero
                                   │ captura on (host)          │ fim da fala
 host ── AUDIO_IN ─► AudioIngest ──┼─ audio ─────────────────►  │
                                   │◄── final {text} ───────── faster-whisper
                                   ├─► TurnManager (source voice)
                                   │◄── AI_RESPONSE / AI_ERROR
                                   ├── speak {text} ─────────► Piper
 host ◄── audio.play + AUDIO_OUT ──┤◄── tts + PCM 22,05 kHz ──┘
```

Protocolo núcleo ↔ sidecar, no socket `/run/zordon-voice/voice.sock`: cada mensagem é um cabeçalho JSON
precedido do tamanho (u32 big-endian) e seguido de `bytes` de carga, quando há.

| Direção | Mensagem |
|---|---|
| sidecar → núcleo | `{"ev":"state","state":"starting\|ready\|failed","reason"?}` |
| núcleo → sidecar | `{"op":"listen","id"}`, `{"op":"audio","id","bytes"}` + PCM, `{"op":"stop","id"}`, `{"op":"cancel","id"}` |
| sidecar → núcleo | `{"ev":"speech","id"}`, `{"ev":"final","id","text","confidence","reason"?}` |
| núcleo → sidecar | `{"op":"speak","id","text"}` |
| sidecar → núcleo | `{"ev":"tts","id","rate","bytes"}` + PCM, `{"ev":"tts_end","id"}` |

Privacidade: a captura liga só durante uma escuta pedida pelo usuário (como o
teste do microfone da SPEC-009), com teto de 15 s, e aparece na pílula. O modo
escolhido não liga o microfone nesta etapa: `wake`, `push` e `open` ficam
indisponíveis com o motivo "chega com a palavra de ativação".

## 6. Fluxo

1. Clique na esfera → `voice.startListening` → captura liga com stream novo →
   núcleo manda `listen` ao sidecar e repassa cada frame.
2. VAD vê fala e depois 0,6 s de silêncio → sidecar transcreve → `final`.
3. Texto vazio → volta ao repouso, sem comando. Texto → turno de voz; pílula
   "Pensando…".
4. Resposta do turno → `speak` → PCM → `audio.play` ao host → `AUDIO_OUT` no
   ritmo da fala → `AUDIO_END` → pílula "Falando…" até o fim.
5. Erro do turno → o Zordon fala "Não consegui responder agora." e a conversa
   mostra o erro.

## 7. Interfaces

| Método | Direção | Formato |
|---|---|---|
| `voice.startListening` | cliente → núcleo | `{reason: "ui"}` → snapshot; sem host ou motor, `ERR_BRIDGE_UNAVAILABLE` / `ERR_AI_UNAVAILABLE` |
| `voice.stopListening` | cliente → núcleo | `{}` → snapshot |
| `audio.play` | núcleo → host | `{streamId, format: {rate, channels, encoding}}` → `{accepted}` |
| `audio.stop` | núcleo → host | `{streamId}` → `{}` |
| `AUDIO_OUT` (`0x02`) | núcleo → host | PCM 22 050 Hz, mono, s16le |

Evento `VOICE_STOPPED {text, confidence, durationMs}` a cada transcrição, como
no catálogo do ZWP.

## 8. Eventos

`VOICE_STATE` com `activity`; `VOICE_STOPPED`; `USER_COMMAND` com `source:
"voice"`; `VOICE_LEVEL` durante a escuta.

## 9. Dados

- Nada de áudio em disco: frames e PCM de síntese só em memória.
- Modelos em `~/.zordon/models` (≈ 550 MB), venv em `~/.zordon/venv` (≈ 480 MB).
- A conversa de voz é uma sessão comum do `ConversationStore`.

## 10. Segurança

- Socket em `/run/zordon-voice/voice.sock`, diretório 0700 criado e removido pelo
  systemd (`RuntimeDirectory`): só o usuário conversa com o motor, e nenhum
  código do Zordon apaga socket velho.
- Dependências Python instaladas com `--require-hashes`; modelos de revisão fixa,
  conferidos por SHA-256 — modelo é código que executa ([Componentes §5](../../architecture/components.md#modelos-de-ml)).
- O sidecar não conhece agentes nem ferramentas: recebe áudio, devolve texto;
  recebe texto, devolve áudio ([Voz §7](design.md#7-sidecar-zordon-voice)).
- A transcrição passa pelo mesmo turno que o texto digitado: nenhuma ação nova é
  possível por voz nesta etapa.

## 11. Permissões

Falar é ação do usuário, iniciada por clique. Nenhum agente inicia escuta.

## 12. Observabilidade

- Sidecar no `journalctl -u zordon-voice`: carga dos modelos, cada transcrição
  com duração e tempo de processamento (sem o texto), falhas.
- Núcleo: INFO a cada escuta (início, fim, motivo) e fala.
- Tela de Voz: pílula com a atividade; Ajustes mostram o motor.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Motor não instalado | Estado `absent`; esfera não escuta; Ajustes dizem como instalar |
| Motor carregando | `starting`; escuta recusada com "o motor de voz está carregando" |
| Sidecar cai no meio | Escuta encerrada, captura desliga, estado `starting` até voltar |
| Nenhuma fala em 6 s | Escuta encerra sem comando |
| Transcrição vazia | Sem comando; pílula volta ao repouso |
| Host sem reprodução | Resposta fica só na conversa; aviso no log |
| Turno falha | Fala "Não consegui responder agora." |

## 14. Testes

- Python (sem dependências pesadas, `python3 -m unittest`, no `verifyAll`):
  moldura do protocolo, máquina de fim de fala com probabilidades sintéticas,
  normalização.
- Núcleo: `SidecarVoiceEngine` contra um sidecar falso em Java no socket;
  sessão de escuta ponta a ponta com host de teste e sidecar falso.
- Host: reprodução com saída falsa.
- Desktop: gatilho da esfera, rótulos da pílula.
- Verificação real: host de teste injeta a frase "que horas são?" sintetizada pelo
  Piper; o sidecar real transcreve, o núcleo responde, o áudio volta ao host de
  teste. Depois, o owner fala no microfone real.

### Evidências (2026-09-18)

- `install-voice.sh --sem-unit`: venv com as 25 dependências instaladas por hash;
  6 arquivos de modelo (≈ 550 MB) baixados de revisão fixa e conferidos por
  SHA-256; units instaladas; `zordon-voice` e `zordon` ativos, o núcleo conectado
  ao socket e o motor pronto em ≈ 3 s (carga 1,8 s + aquecimento 1,5 s).
- Ponta a ponta no serviço real, com um host de teste que devolve como microfone
  a frase "Que horas são?" sintetizada pelo Piper:

  | Tempo | Acontecimento |
  |---|---|
  | 0,34 s | clique → captura ligada, `listening` |
  | 2,00 s | fim da fala (VAD) → captura desligada, `understanding` |
  | 3,52 s | transcrição "Que horas são?" (confiança 0,93) → turno de voz → rota rápida "São 19h21." |
  | 3,68 s | narração → síntese → `audio.play`, `speaking` |
  | 5,68 s | `AUDIO_END` após 2,39 s de fala ("São dezenove horas e vinte e um minutos.") → `idle` |

  Do fim real da fala ao início da resposta: ≈ 2,2 s, só com CPU.
- Teste real aprovado pelo owner em 2026-09-18: falou no microfone real e ouviu a
  resposta nas caixas de som ("sim a voz funcionou").

## 15. Critérios de aceite

- `CA-1` Dado o sidecar, então ele anuncia `starting` ao carregar e `ready` depois
  de aquecer os modelos; o núcleo reflete isso no estado do motor e volta a
  `starting` se o sidecar cair.
- `CA-2` Dada uma escuta, então a captura liga por no máximo 15 s, os frames vão ao
  sidecar, e ela termina 0,6 s depois do fim da fala, sem fala em 6 s, ou por
  `voice.stopListening`, desligando a captura.
- `CA-3` Dada uma transcrição não vazia, então ela vira um turno com `source:
  "voice"` e `VOICE_STOPPED`; vazia, não vira nada.
- `CA-4` Dada a resposta do turno, então o núcleo a sintetiza pelo sidecar e a
  toca no host com `audio.play`, `AUDIO_OUT` e `AUDIO_END`; erro do turno toca "Não
  consegui responder agora.".
- `CA-5` Dada a escuta, o pensamento e a fala, então o snapshot traz `activity`
  `listening`, `thinking` e `speaking`, e o estado visual da SPEC-012 acompanha.
- `CA-6` Dado o host, então `audio.play` abre a saída no formato pedido, os
  frames tocam em ordem e `AUDIO_END` drena e fecha; `audio.stop` corta na hora.
- `CA-7` Dado o desktop, então clicar na esfera ou Ctrl+Espaço inicia a escuta, e
  clicar de novo a encerra.
- `CA-8` Dada a fala "que horas são?", com ou sem ponto final, então a rota rápida
  responde, e a fala sai normalizada ("quinze horas e quarenta minutos").
- `CA-9` Dados o protocolo do socket e o VAD, então a moldura faz ida e volta e o
  fim de fala segue os tempos do CA-2 (testes Python).
- `CA-10` Dados `install-voice.sh` e a unit, então as dependências são instaladas
  com hash, os modelos conferidos por SHA-256 e a unit tem `PartOf=zordon.service`.

## 16. Impacto em outros módulos

- `voice/` (novo, Python), `packaging/wsl/install-voice.sh`, `zordon-voice.service.template`.
- `zordon-core`: `SidecarVoiceEngine`, sessão de escuta no `VoiceService`,
  `VoiceMethods`, `IntentRouter`.
- `zordon-zwp`: `CoreConnection` repassa frames binários ao listener.
- `zordon-host`: `Speaker`, `audio.play`, `audio.stop`, capacidade `audio.playback`.
- `zordon-desktop`: gatilho na esfera, atalho, rótulos.
- Documentação: [ZWP](../../api/zwp-protocol.md), [Voz §7](design.md#7-sidecar-zordon-voice),
  [Instalação](../../operations/install.md), [Primeiros passos](../../operations/quickstart.md).

## 17. Dependências

- [SPEC-006](SPEC-006-tela-e-estado-da-voz.md) · [SPEC-007](../host/SPEC-007-host-do-windows.md) ·
  [SPEC-009](SPEC-009-frames-de-audio-e-teste-do-microfone.md) · [SPEC-010](../ui/SPEC-010-shell-compacto-centrado-na-voz.md)
- [ADR-0009](../../adr/ADR-0009-captura-windows-inferencia-wsl.md) · [ADR-0028](../../adr/ADR-0028-motor-de-voz-como-unit-systemd.md)
