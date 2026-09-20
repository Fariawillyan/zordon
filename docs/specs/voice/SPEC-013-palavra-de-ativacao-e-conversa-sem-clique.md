---
document: spec-013
module: voice
section: spec
version: 1
updatedAt: 2026-09-18
securityLevel: internal
tags: [spec,voz,wake-word,zordon,barge-in,modos,treino,privacidade]
specId: SPEC-013
---

# SPEC-013 — Palavra de ativação e conversa sem clique

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `VoiceAgent` |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M2 |
| **Decisão** | [ADR-0038](../../adr/ADR-0038-palavra-de-ativacao-treinada-aqui.md) |

## 1. Objetivo

Aprovada pelo owner em 2026-09-18, em resposta ao pedido de aprovação ("sim …
continue").

Dizer "Zordon, que horas são?" com a janela fechada e ouvir a resposta, sem clicar
em nada. É a etapa 2 do motor de voz e o critério de pronto do M2.

## 2. Problema

A [SPEC-011](SPEC-011-motor-de-voz-ouvir-e-falar.md) ouve só depois de um clique
na esfera. Por isso os modos `wake` e `open` ficam indisponíveis com o motivo
"sem palavra de ativação". Também não há como interromper o Zordon enquanto ele
fala.

Não existe detector pronto de "Zordon" com licença compatível, e transcrever tudo
para procurar a palavra foi medido e não funciona (ADR-0038). O detector precisa
ser treinado aqui.

## 3. Escopo

**Detector (motor Python)**

- `WakeDetector` em fluxo: `melspectrogram.onnx` e `embedding_model.onnx` (Apache
  2.0) com o classificador `zordon-wake-v1` (MLP em numpy). Avalia a cada 80 ms
  sobre 16 embeddings (≈ 2 s de áudio). O mel é local por quadro, então o fluxo
  vê exatamente as features do treino.
- Dispara só se o limiar for cruzado **e** o VAD tiver visto fala no mesmo trecho.
  Depois de disparar, fica 2 s sem disparar de novo.

**Treino (tarefa de desenvolvimento, fora da instalação)**

- `voice/training/`: receita reprodutível, com as fontes (URL, revisão e SHA-256)
  num `training.lock`, usando o venv do motor e semente fixa para amostragem e
  treino. A síntese Piper tem ruído interno não semeável: reproduz a distribuição,
  não os mesmos bytes; o artefato final é identificado pelo SHA-256.
- **Positivos.** Vozes Piper PT-BR (faber, cadu, jeff) com as grafias "Zordon",
  "Zórdon" e "Zôrdon", sozinhas, com vocativo ("Ei, Zordon") e seguidas de
  comando. Também a voz LibriTTS-R, com 904 locutores. Variação de velocidade,
  ruído, ganho e reverberação sintética. A edresson não tem o fonema nasal (o
  "Zordon" dela sai "Zordo") e entra só nos negativos.
- **Negativos.**
  - Fala do MLS português, lida em fluxo e só até a cota de horas.
  - Frases parecidas sintetizadas ("Gordon", "Jordão", "cordão", "Zorro", "sordo",
    "condor", "ordem").
  - Silêncio e ruído.
- **Separação para teste.**
  - Uma voz PT-BR e 10% dos locutores LibriTTS ficam fora do treino.
  - O teste de falso positivo usa o conjunto de teste do MLS (locutores que o treino
    não viu), com pelo menos 10 h.
  - Cada variante de ruído, contexto e reverberação conta separadamente no
    acerto; acertar uma variante não encobre as outras.
- **Saída.**
  - `voice/models/zordon-wake-v1.npz`, com o hash no `models.lock`.
  - Um relatório com acerto, falsos positivos por hora e o limiar escolhido.

**Máquina de estados (núcleo)**

```text
             "Zordon"                 fim da fala
 DORMINDO ─────────────► ESCUTANDO ─────────────► PENSANDO
    ▲  ▲   (tom curto)      │  6 s sem fala            │
    │  └────────────────────┘                          ▼
    │                         "Zordon" (barge-in)   FALANDO
    └──────────── fim do áudio ─────────────────────── │
                                     ESCUTANDO ◄───────┘
```

- **`wake`:** o microfone fica ligado e o motor, em `DORMINDO`, só procura a
  palavra.
  - Detectada, o host toca um tom curto e a escuta abre.
  - A escuta aceita o comando no mesmo fôlego ("Zordon, que horas são?") ou logo
    depois ("Zordon." … "que horas são?").
  - A palavra não entra no comando.
- **`open`:** cada fala vira comando, sem a palavra, por 5 min; depois volta a
  `wake`. A pílula mostra o prazo o tempo todo.
- **`push`:** fica indisponível, com motivo próprio. O atalho global exige código
  nativo no host, e isso é decisão de outra SPEC, com o overlay.
- **Meia-duplexação (barge-in do M2)**
  - Enquanto o Zordon fala, o que o microfone capta não vira comando.
  - Só a palavra interrompe: a fala para, a fila da placa esvazia e a escuta
    abre.
  - Fala do próprio Zordon que contenha "Zordon" desarma a palavra até acabar,
    para ele não se acordar sozinho.
- **Confiança:** transcrição com confiança abaixo de 0,6 não age. O Zordon diz
  "Não entendi." e volta a dormir ([Voz §6](design.md#6-privacidade-e-falsos-positivos)).
  Vale também para a escuta por clique.

**Desktop**

- Pílula:
  - "Aguardando “Zordon”" em `DORMINDO`;
  - os estados da SPEC-006 e da SPEC-012 a partir da escuta;
  - "Conversa aberta até HH:MM" no modo `open`;
  - "Desligar" sempre que a captura estiver ligada.
- Ajustes: `wake` e `open` disponíveis; `push` indisponível, com o motivo.

## 4. Não escopo

- Atalho global, modo `push` e overlay: próxima SPEC.
- Porta por correlação e AEC ([Voz §5](design.md#5-barge-in), M6).
- Adaptação à voz do owner com gravações dele. Se o modelo base não bastar em
  campo, vira SPEC própria, e as gravações nunca saem da máquina nem entram no
  repositório.
- Normalização completa de números e siglas para a fala.

## 5. Arquitetura

```text
host (Windows) ── frames 20 ms ──► núcleo ── audio ──► motor
                                     │                  ├─ VAD (Silero)
                                     │                  ├─ WakeDetector ─► ev wake
                                     │                  └─ Endpointer + Whisper ─► ev final
                                     ▼
                         VoiceService (máquina de estados)
                           ├─ tom de escuta ──► SpeechPlayer ──► host toca
                           ├─ comando ──► IntentRouter ──► turno
                           └─ barge-in ──► SpeechPlayer.interrupt + audio.stopPlayback
```

- O motor não decide nada de produto. Ele informa "palavra detectada", "fala
  começou/acabou" e "transcrição".
- Quem decide é o núcleo: abrir a escuta, descartar, interromper, voltar a dormir.
- O áudio vive só em RAM:
  - no motor, um anel de 2 s para o detector e o áudio da escuta atual;
  - no núcleo, nada além do repasse.

## 6. Fluxo

1. `wake` em vigor e host conectado: o núcleo pede captura ao host e abre um
   fluxo `stream` no motor.
2. O motor avalia cada 80 ms e dispara `wake` com a pontuação.
3. O núcleo toca o tom, publica `activity: listening` e passa a esperar o comando.
4. O motor corta o áudio no ponto da detecção e segue com o endpointer da
   SPEC-011.
   - Fim da fala: `final`, com o texto sem a palavra.
   - 6 s sem fala: `final` vazio e volta a `DORMINDO`, sem narração.
5. O núcleo despacha o comando (confiança ≥ 0,6) e marca o fluxo como `speaking`
   enquanto a resposta toca.
6. "Zordon" durante a fala: o núcleo interrompe a fala e volta ao passo 3.
7. `off`: o núcleo fecha o fluxo e pede ao host para desligar a captura. O LED do
   Windows apaga.

## 7. Interfaces

**Protocolo do motor** (socket da SPEC-011, mesmas regras de enquadramento):

| Mensagem | Sentido | Campos |
|---|---|---|
| `state` | motor → núcleo | + `wake: true` quando o detector carregou |
| `stream` | núcleo → motor | `id`, `mode: "wake" \| "open"` |
| `duplex` | núcleo → motor | `id`, `speaking: bool`, `armed: bool` |
| `wake` | motor → núcleo | `id`, `score` |
| `final` | motor → núcleo | como na SPEC-011; num `stream`, o fluxo continua depois |
| `cancel` | núcleo → motor | encerra o fluxo |

**Núcleo**

- `VoiceEngine.wakeWord()` passa a refletir `state.wake`.
- `VoiceEngine.stream(id, mode, listener)` recebe um `Listening` com `wake(score)`.
- `VoiceEngine.duplex(id, speaking, armed)`.
- `SpeechPlayer.interrupt()` e `SpeechPlayer.cue()` (o tom de escuta, gerado no
  núcleo).

**ZWP**

- Nada novo. `voice.setMode` passa a aceitar `wake` e `open` de fato.
- `push` responde `effective: unavailable` com o motivo próprio.

## 8. Eventos

- `VOICE_STATE`: mesmos campos; `activity` passa por `idle` (dormindo),
  `listening`, `thinking` e `speaking`.
- `VOICE_WAKE` (novo): `score`, `outcome` e `bargeIn`.
  - `outcome` é `command`, `silence` ou `low_confidence`; `bargeIn: true`
    identifica a ativação que interrompeu a fala, preservando também seu desfecho.
  - Nunca leva áudio nem o texto de fala descartada.
- O intérprete de atividade (SPEC-012) traduz a palavra e a interrupção em estados
  visuais. O Live Trace registra cada ativação.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Anel de áudio do detector | RAM do motor | 2 s, sobrescrito |
| Áudio da escuta | RAM do motor | Até a transcrição |
| Ativações (`VOICE_WAKE`) | Live Trace | Como o trace (SPEC-012) |
| Modelo `zordon-wake-v1.npz` | `voice/models/` → `~/.zordon/models/wake/` | Versionado; hash no `models.lock` |
| Dados de treino | `~/.zordon/training/` | Local, fora do repositório; nunca apagado pelo Zordon |

## 10. Segurança

- **Microfone contínuo só com o modo pedido.** `wake` e `open` ligam a captura; a
  pílula com "Desligar" fica visível o tempo todo (SPEC-010 §10).
- **Barreiras somadas contra ativação falsa**
  1. palavra acima do limiar;
  2. VAD confirmando fala;
  3. confiança da transcrição ≥ 0,6;
  4. classificação de risco da ação.

  RED nunca executa só por voz ([Voz §6](design.md#6-privacidade-e-falsos-positivos)).
- **Origem `voice`.** Comando por palavra ou em `open` tem origem `voice`, com o
  teto de risco dela ([Identidade](../../security/identity.md)).
- **Modelo é código.** O `.npz` e os `.onnx` são verificados por SHA-256 na
  instalação. Os pesos são carregados com `allow_pickle=False`.
- **Nada de áudio em disco**, nem no treino a partir do microfone (não há
  gravação nesta SPEC).

## 11. Permissões

Nenhuma nova. O modo de voz continua sendo escolha do usuário; nenhum componente
liga `wake` ou `open` sozinho.

## 12. Observabilidade

- Live Trace: cada ativação com pontuação e desfecho, o limiar em vigor e a
  latência de palavra até o tom.
- Métricas do motor: tempo por bloco de 80 ms e CPU em `DORMINDO`.
- O relatório do treino fica versionado junto com o modelo.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Modelo da palavra ausente ou inválido | `wake: false`; `wake` e `open` indisponíveis com o motivo; clique continua funcionando. Hash errado nem chega a ser instalado |
| Motor cai com a escuta aberta | Volta a `DORMINDO` quando o motor voltar; sem comando pela metade |
| Host cai em `wake` | Modo indisponível ("sem host"); nada é capturado |
| "Zordon" sem comando | 6 s e volta a dormir, calado |
| Transcrição com confiança baixa | "Não entendi."; nada executa |
| Ativação durante um turno em andamento | Interrompe a fala, não o turno; o comando novo entra na fila da conversa |
| `open` expira no meio de uma fala | Termina a fala atual e volta a `wake` |

## 14. Testes

- **Motor (unittest)**
  - `WakeDetector` com áudio dourado: "Zordon" sintetizado dispara, e frases
    parecidas não.
  - Refratário de 2 s.
  - Corte do comando no ponto da detecção.
  - `duplex` desarmando.
- **Treino**
  - Avaliação sobre o conjunto separado: acerto, falsos positivos por hora e
    curva por limiar.
  - O relatório é a evidência do CA-1.
- **Núcleo**
  - Máquina de estados com motor falso: dormir → escutar → pensar → falar → dormir.
  - Silêncio depois da palavra, barge-in, confiança baixa, expiração do `open`,
    `push` indisponível e autoativação desarmada.
- **Real** (owner)
  - "Zordon, que horas são?" com a janela fechada.
  - 8 h de fala ambiente com `wake` ligado, contando ativações no trace.

### Evidências do treino (2026-09-19)

Cinco tentativas, arquivadas em `~/.zordon/training/attempts/`. O modelo
versionado é o da rodada 5, com limiar de operação 0,99
([relatório](../../../voice/training/report-zordon-wake-v1.md)).

| Modelo, no mesmo teste | Limiar | Acerto | Falsos em 10 h de MLS | Falsos sintéticos |
|---|---|---|---|---|
| Rodada 4 | 0,99 | 63,1% | 7 | 9 |
| **Rodada 5** | **0,99** | **71,2%** | **7** | **7** |
| Rodada 5 | 0,995 | 67,0% | 4 | 6 |

- **O CA-1 não está atendido:** o critério pede ≥ 95% de acerto e ≤ 1 falso em
  10 h; no limiar escolhido, o acerto é de 71% com 7 falsos (0,7 por hora).
- **Amostras douradas** (voz do Zordon, determinística):
  - "Zordon!" pontua 0,996 e "Ei, Zordon." 0,999: disparam.
  - "Gordon." pontua 0,964, "O rio Jordão." 0,772 e "Que horas são?" 0,06: não
    disparam.
  - "Zórdon, que horas são?" dito de um fôlego pontua 0,80 e **não dispara**
    (na rodada 4, 0,28).
  - Os dois testes da frase de um fôlego estão marcados como falha esperada.
    Quando um modelo passar, o `unittest` acusa e a marca sai.
- **Uso prático até lá:** dizer "Zordon", esperar o tom e então o pedido. Isso
  é o CA-4 "em duas falas", que funciona.
- **Pendente do owner:** o teste de campo (CA-13 e CA-14), que decide se o
  limiar fica em 0,99 ou sobe para 0,995.

## 15. Critérios de aceite

- `CA-1` Dado o conjunto separado do treino, então o detector acerta ≥ 95% dos
  positivos e dispara ≤ 1 vez em 10 h de fala do MLS de teste, no limiar
  escolhido.
- `CA-2` Dado o motor em `DORMINDO`, então cada bloco de 80 ms é avaliado em
  < 10 ms (p95) e o motor em silêncio usa < 3% de um núcleo.
- `CA-3` Dado "Zordon" detectado, então o tom de escuta toca no host em < 300 ms e
  a atividade vira `listening`.
- `CA-4` Dado "Zordon, que horas são?" num fôlego só ou em duas falas, então o
  comando despachado é "que horas são?", sem a palavra.
- `CA-5` Dado "Zordon" e 6 s de silêncio, então nada é despachado, nada é falado e
  o motor volta a `DORMINDO`.
- `CA-6` Dado o modo `open`, então cada fala vira comando sem a palavra, a pílula
  mostra o prazo e, em 5 min, o modo volta a `wake`.
- `CA-7` Dado o modo `push`, então ele fica indisponível com motivo próprio e não
  liga o microfone.
- `CA-8` Dado o Zordon falando, então a fala captada não vira comando e "Zordon"
  para a fala em < 300 ms e abre a escuta.
- `CA-9` Dada uma fala do Zordon que contenha "Zordon", então ela não o ativa.
- `CA-10` Dada uma transcrição com confiança < 0,6, então nada executa e o Zordon
  diz "Não entendi.".
- `CA-11` Dada uma ativação, então o trace registra pontuação e desfecho e nunca
  áudio nem o texto de fala descartada.
- `CA-12` Dado o modelo ausente ou inválido (o hash é conferido na instalação),
  então `wake` e `open` ficam indisponíveis com o motivo e o clique continua
  funcionando.
- `CA-13` (real) Com a janela fechada, "Zordon, que horas são?" é respondido por
  voz em < 2,5 s (p50) do fim da fala.
- `CA-14` (real) Em 8 h de fala ambiente com `wake`, há ≤ 1 ativação falsa.

## 16. Impacto em outros módulos

- `voice/`: `wake.py` (detector), `server.py` (`stream` e `duplex`),
  `training/` (receita, lock e relatório), `models.lock`, `requirements.lock`
  (sem pacote novo em execução).
- `zordon-core`: `VoiceService` (máquina de estados), `SidecarVoiceEngine`,
  `SpeechPlayer` (`cue` e `interrupt`), `ActivityInterpreter`, `EventType.VOICE_WAKE`.
- `zordon-desktop`: textos da pílula e dos Ajustes.
- `packaging/wsl/install-voice.sh`: instala o modelo da palavra e os `.onnx` de
  features.
- `NOTICE`: atribuição do MLS, da LibriTTS-R, do corpus do edresson e do
  openWakeWord.
- SPEC-011: o CA de escuta por clique ganha a regra de confiança (CA-10 daqui).

## 17. Dependências

- [SPEC-011](SPEC-011-motor-de-voz-ouvir-e-falar.md) · [SPEC-012](SPEC-012-voice-first-narracao-e-estados.md) ·
  [SPEC-010](../ui/SPEC-010-shell-compacto-centrado-na-voz.md) ·
  [ADR-0038](../../adr/ADR-0038-palavra-de-ativacao-treinada-aqui.md) ·
  [Voz — design](design.md)
