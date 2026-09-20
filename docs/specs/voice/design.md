---
document: spec-voice-design
module: voice
section: pipeline
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [wake-word,vad,stt,tts,latencia]
specId: null
---

# Voz — pipeline

## 1. Pipeline

```text
  WINDOWS (zordon-host)                    WSL (zordon-core + zordon-voice)
  ─────────────────────                    ────────────────────────────────

  microfone
     │  javax.sound.sampled
     │  16 kHz, mono, s16le, frames de 20 ms
     ▼
  porta de ruído (RMS)           ──── só envia se houver energia acima do piso
     │
     │  ZWP binário 0x01  (32 KB/s)
     └───────────────────────────────►  buffer circular (pré-roll 1,5 s)
                                            │
                                            ▼
                                       Silero VAD  (ONNX, ~1 MB)
                                            │ há fala?
                                            ▼
                                       openWakeWord "zordon"
                                            │ detectou?  ──► evt VOICE_STARTED
                                            ▼
                                       janela de escuta (máx. 15 s)
                                            │
                                            ▼
                                       faster-whisper (STT)
                                            │ parciais ──► evt VOICE_PARTIAL
                                            │ final    ──► evt VOICE_STOPPED
                                            ▼
                                       ZORDON CORE → IntentRouter → LLM
                                            │
                                            ▼
                                       Piper (TTS)
                                            │
  alto-falante ◄──── ZWP binário 0x02 ──────┘
```

Decisão-chave: **captura no Windows, inferência no WSL**. O WSL não tem acesso
confiável a dispositivo de áudio ([R6](../../architecture/windows-wsl.md#r6--não-há-áudio-confiável-dentro-do-wsl))
e todo o ecossistema de modelos de voz é Python/ONNX, que vive melhor no Linux
com CUDA. Justificativa completa em
[ADR-0009](../../adr/ADR-0009-captura-windows-inferencia-wsl.md).

Custo de trafegar áudio bruto: 16.000 amostras/s × 2 bytes = **32 KB/s**. Em
loopback, irrelevante. Foi a percepção desse número que permitiu escolher a
arquitetura simples em vez de distribuir os modelos entre os dois lados.

## 2. Escolhas de tecnologia

| Etapa | Escolha | Licença | Alternativa | Por quê |
|---|---|---|---|---|
| Captura | `javax.sound.sampled` | JDK | WASAPI via JNA | Sem dependência nativa; suficiente para 16 kHz mono |
| VAD | Silero VAD (ONNX) | MIT | WebRTC VAD | Muito melhor com ruído; ~1 MB; roda em CPU |
| Wake word | Features openWakeWord + classificador `zordon-wake-v1` | Apache-2.0 (features e código) | Porcupine | Classificador próprio; fontes de treino no lock da SPEC-013 |
| STT | faster-whisper (CTranslate2) | MIT | Vosk, whisper.cpp | Melhor qualidade em PT-BR; CUDA no WSL2 |
| TTS | Piper | MIT | Coqui, TTS de nuvem | Rápido, local, vozes PT-BR decentes |

Sobre **Porcupine**: é mais preciso e tem SDK Java, mas o uso comercial exige
licença e a palavra custom é gerada em portal deles. Para um projeto pessoal
funcionaria; a escolha por openWakeWord preserva a opção de o projeto crescer
sem renegociar licença. É uma decisão reversível — o detector fica isolado em
`voice/zordon_voice/wake.py`.

Sobre a **palavra "Zordon"**: o detector próprio usa positivos sintetizados com
Piper e negativos de fala portuguesa e palavras parecidas. Os modelos prontos de
palavras do openWakeWord não são usados. As fontes, a separação de locutores e
os critérios de avaliação estão na
[SPEC-013](SPEC-013-palavra-de-ativacao-e-conversa-sem-clique.md) e na
[ADR-0038](../../adr/ADR-0038-palavra-de-ativacao-treinada-aqui.md).
Meta de campo: taxa de falso positivo abaixo de **1 por 8 horas** de fala
ambiente, e taxa de falso negativo abaixo de **5%** a 2 metros do microfone.

## 3. Máquina de estados

```text
      ┌──────────┐  wake word          ┌───────────┐
      │ DORMINDO ├────────────────────►│ ESCUTANDO │
      │          │  hotkey / UI        │           │
      └────▲─────┘                     └─────┬─────┘
           │                                 │ fim de fala (VAD)
           │ timeout 6 s sem fala            │ ou 15 s (teto)
           │ ou cancelamento                 ▼
           │                           ┌───────────┐
           │                           │ PENSANDO  │
           │                           └─────┬─────┘
           │                                 │ resposta pronta
           │                                 ▼
           │                           ┌───────────┐  usuário fala (barge-in)
           └───────────────────────────┤ FALANDO   ├──────────────► ESCUTANDO
                      fim do áudio     └───────────┘
```

Modos de operação (`voice.setMode`):

| Modo | Comportamento |
|---|---|
| `off` | Microfone desligado no host. Nenhum frame é capturado |
| `wake` | Padrão. Wake word ativa |
| `push` | Só grava enquanto a hotkey global estiver pressionada |
| `open` | Conversa contínua sem wake word, por 5 min, com indicador persistente |

`off` desliga a captura **no host**, não filtra no núcleo. É a diferença entre
"não estamos ouvindo" e "estamos ouvindo e descartando"; para o microfone, só a
primeira é aceitável, e o LED de uso de microfone do Windows precisa refletir
isso.

## 4. Orçamento de latência

Alvo de UC1 ("Zordon, que horas são?"): **p50 de 1,8 s** do fim da fala ao
primeiro fonema da resposta.

| Etapa | Alvo p50 | Observação |
|---|---|---|
| Buffer de captura + rede | 40 ms | 2 frames de 20 ms |
| VAD detecta fim de fala | 200 ms | Silêncio necessário para não cortar o usuário |
| STT (faster-whisper small, GPU) | 350 ms | Áudio de ~3 s; em CPU sobe para ~900 ms |
| Intent Router (rota rápida) | 5 ms | Casou regra determinística |
| Intent Router (LLM pequeno) | 400 ms | Quando a rota rápida não casa |
| Contexto + seleção de ferramentas | 60 ms | Embeddings em cache |
| Primeiro token do LLM | 700 ms | Depende do provider e do cache de prompt |
| TTS até o primeiro fonema | 250 ms | Piper sintetiza por sentença, não pelo texto todo |
| Rede + buffer de reprodução | 60 ms | |
| **Total (rota rápida)** | **~1,0 s** | |
| **Total (com LLM)** | **~1,7 s** | |

Duas técnicas compram a maior parte da margem:

- **TTS por sentença.** Assim que a primeira sentença da resposta fecha, ela já
  vai para o Piper enquanto o LLM continua gerando. O usuário ouve o início da
  resposta antes de o modelo terminá-la.
- **Rota rápida no Intent Router.** Comandos frequentes ("abra o X", "que horas
  são", "pausa") não chamam LLM nenhum.
  Ver [Core §2](../core/design.md#2-intent-router).

Se o p50 medido estourar 2,5 s, a causa mais provável é STT em CPU por disputa
de GPU ([R14](../../architecture/windows-wsl.md#r14--a-gpu-é-disputada)) — a tela de
Diagnostics precisa mostrar essa decomposição por etapa para que o diagnóstico
seja direto.

## 5. Barge-in

O usuário precisa poder interromper o Zordon falando. O problema é o eco: o
microfone capta o próprio alto-falante e o VAD acha que é fala.

Abordagem em ordem de custo crescente:

1. **Meia-duplexação com wake word (M2).** Durante `FALANDO`, apenas a wake word
   pode interromper, não o VAD. Simples, robusto, e "Zordon, para" funciona.
2. **Porta por correlação (M6).** O host conhece o que está tocando; suprime a
   detecção quando a energia do microfone se correlaciona com o sinal de saída.
3. **AEC de verdade.** Só se 1 e 2 não bastarem. Requer processamento de sinal
   específico e é a única parte da voz que justificaria código nativo.

`voice.interrupt` para o TTS imediatamente (descarta o stream, esvazia a fila da
placa) e cancela o turno em andamento.

## 6. Privacidade e falsos positivos

Compromissos que o design assume explicitamente:

| Compromisso | Implementação |
|---|---|
| Áudio bruto nunca sai da máquina | STT local por padrão; STT em nuvem exige opt-in por configuração, com aviso |
| Áudio não é persistido | O buffer de pré-roll é em RAM e sobrescrito; nada em disco |
| O usuário sabe quando está sendo ouvido | Ícone do tray muda; overlay aparece; beep curto ao abrir a escuta |
| Falso positivo não vira comando | Janela de escuta que expira sem fala descarta tudo, sem evento `USER_COMMAND` |
| Transcrição errada não age sozinha | Confiança de STT abaixo de 0,6 → o Zordon pede confirmação em vez de agir |
| O usuário pode auditar | A tela de Logs mostra toda ativação, inclusive as descartadas |

O caso perigoso é a combinação falso positivo + transcrição ruim + ação
destrutiva. As defesas se somam: a wake word precisa disparar, a transcrição
precisa ter confiança, a ação precisa ser classificada, e se for RED o usuário
precisa confirmar lendo o alvo concreto. Uma conversa aleatória na sala não
atravessa essas quatro barreiras.

Uma escolha de produto derivada disso: **ações RED nunca são executadas em fluxo
puramente de voz sem confirmação visual.** Confirmar exclusão de arquivos
dizendo "sim" é frágil demais; o diálogo aparece na tela (e o overlay chama
atenção se a janela estiver fechada).

## 7. Sidecar `zordon-voice`

Processo Python filho do núcleo, iniciado e supervisionado por ele.

| Aspecto | Decisão |
|---|---|
| Ciclo de vida | Filho do `zordon-core`; morre com ele; reinicia com backoff |
| IPC | Socket de domínio Unix em `~/.zordon/run/voice.sock`, frames com cabeçalho de tamanho |
| Ambiente | venv em `~/.zordon/venv`, criado na instalação a partir de `requirements.lock` |
| Modelos | `~/.zordon/models/`, baixados com verificação de SHA-256 |
| Descarga | STT sai da memória após 10 min sem uso; VAD e wake word ficam sempre |
| GPU | `adaptive` por padrão — ver [R14](../../architecture/windows-wsl.md#r14--a-gpu-é-disputada) |
| Falha | Se o sidecar não subir, o núcleo funciona por texto e reporta `voice: unavailable` |

O sidecar não tem lógica de produto: ele recebe áudio, devolve eventos de
detecção e texto, recebe texto e devolve áudio. Ele não conhece agentes,
ferramentas nem permissões. Isso o mantém substituível — trocar faster-whisper
por outra coisa é alterar um arquivo.

Interface (esboço, JSON por linha sobre o socket):

```text
→ {"op":"audio","stream":1,"seq":4421}  + payload PCM
← {"ev":"vad","speech":true}
← {"ev":"wake","word":"zordon","score":0.91}
← {"ev":"partial","text":"quais containers"}
← {"ev":"final","text":"quais containers estão rodando","confidence":0.94}
→ {"op":"tts","text":"Você tem 8 containers rodando.","voice":"pt_BR-faber-medium"}
← {"ev":"tts_chunk","stream":2}  + payload PCM
```

## 8. Localização

- Wake word: modelo treinado para pronúncia PT-BR de "Zordon".
- STT: `faster-whisper` com `language="pt"` fixado (detecção automática é mais
  lenta e erra em frases curtas, que são justamente o caso de uso).
- TTS: voz PT-BR do Piper, selecionável nas configurações.
- Números, horas e siglas técnicas em respostas faladas precisam de normalização
  antes do TTS ("8 containers" → "oito contêineres"; "CPU" → "cê pê u";
  "22:31" → "vinte e duas e trinta e um"). Sem isso, a fala fica ruim de um jeito
  que o usuário percebe imediatamente. Essa normalização é do sidecar.
