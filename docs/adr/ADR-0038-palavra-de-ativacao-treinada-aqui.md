---
document: adr-0038
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,voz,wake-word,licenca,treino,openwakeword]
specId: SPEC-013
---

# ADR-0038 — A palavra "Zordon" tem detector próprio, treinado aqui com dados de licença permissiva

**Status:** Aceito · 2026-09-18

## Contexto

O [design da voz](../specs/voice/design.md#2-escolhas-de-tecnologia) escolheu o
openWakeWord para detectar "Zordon". Duas coisas apareceram ao implementar:

1. **Não existe modelo pronto de "Zordon"** e os modelos prontos do openWakeWord
   ("hey jarvis", "alexa"…) são **CC BY-NC-SA 4.0**, assim como o conjunto de
   negativos que o treino dele usa (ACAV100M, 17 GB). Nada disso cabe num projeto
   Apache 2.0 ([Cadeia de suprimentos](../security/supply-chain.md)).
2. **Transcrever tudo e procurar a palavra não funciona.** Medido em 2026-09-18
   com frases sintetizadas (21 positivas, 72 negativas, 3 velocidades), só a CPU:

   | Porteiro | Acertos | Falsos positivos | p50 |
   |---|---|---|---|
   | Whisper tiny, com `hotwords` | 8/21 | 0/72 | 270 ms |
   | Whisper base, com `hotwords` | 4/21 | 3/72 | 470 ms |
   | Whisper small, com `hotwords` | 6/21 | 2/72 | 1,3 s |
   | Qualquer tamanho, sem `hotwords` | 0/21 | 0/72 | — |

   O Whisper ouve "do dom", "Tudo bom" e "Todom"; com viés, troca "Jordão" e
   "Sordo" por "Zordon". Transcritor não é detector de palavra-chave.

O que se aproveita do openWakeWord é Apache 2.0: o código, o `melspectrogram.onnx`
e o `embedding_model.onnx` (o *speech embedding* do Google, Apache 2.0). O próprio
projeto documenta que esse embedding generaliza bem mesmo com positivos
totalmente sintéticos.

## Alternativas

| Alternativa | Por que não |
|---|---|
| Whisper como porteiro | Medido acima: não acerta a palavra e erra para o lado perigoso |
| Modelos e negativos do openWakeWord | CC BY-NC-SA: incompatível com Apache 2.0 |
| Porcupine | Licença comercial e palavra gerada no portal do fornecedor |
| Vosk com gramática restrita | "Zordon" fica fora do léxico; exigiria recompilar o grafo |
| Pacote `openwakeword` completo | Puxa `tflite-runtime` e scikit-learn para rodar três redes pequenas |
| **Features Apache 2.0 do openWakeWord + classificador próprio treinado aqui** | — |

## Decisão

- **Em execução:** o motor de voz roda `melspectrogram.onnx` e
  `embedding_model.onnx` com o `onnxruntime` que já tem, e um classificador
  pequeno (MLP sobre 16 embeddings, aproximadamente 2 s de áudio) com pesos em `.npz`
  executado em numpy. Não entra o pacote `openwakeword`, nem `tflite`, nem torch.
- **No treino:** positivos sintéticos com as vozes PT-BR do Piper (datasets CC0 e
  CC BY 4.0) e a voz multilocutor LibriTTS-R (CC BY 4.0, 904 locutores),
  negativos da fala do MLS português (CC BY 4.0) e de frases parecidas
  sintetizadas ("Gordon", "Jordão", "cordão", "Zorro"). Sem ACAV100M e sem os
  modelos prontos.
- **Revisão de 2026-09-19 (tentativas 3 a 5):** entram como negativos a fala
  real do MLS italiano e do LibriSpeech `train-clean-100` (ambos CC BY 4.0,
  fixados no `training.lock`), e como positivos a palavra dita dentro de frases
  ("Zordon, que horas são?"), com o fim da palavra marcado pela primeira pausa.
- **Reprodutível:** fontes com URL, revisão e SHA-256 num lock do treino; semente
  fixa; o modelo gerado entra no `models.lock` com o seu hash, como todo modelo
  ([SPEC-011](../specs/voice/SPEC-011-motor-de-voz-ouvir-e-falar.md)).
- **Atrás de uma interface:** `WakeDetector` no motor. Trocar por Porcupine, por um
  modelo adaptado à voz do owner ou por outro detector é trocar a implementação.

Detalhes em [SPEC-013](../specs/voice/SPEC-013-palavra-de-ativacao-e-conversa-sem-clique.md).

## Consequências

- A palavra de ativação passa a ser um artefato do projeto, com licença limpa e
  número medido, não uma dependência opaca.
- O treino é tarefa de desenvolvimento, não de instalação: usa o venv do motor
  e baixa o arquivo MLS português (2,60 GB), além das vozes e features travadas
  em `training.lock`. A extração respeita as cotas de horas; o tempo depende da CPU.
- Os datasets CC BY 4.0 pedem atribuição no `NOTICE`.
- A meta de falso positivo (≤ 1 por 8 h de fala ambiente) só se prova em campo; o
  conjunto de teste do treino é o primeiro filtro, não a prova.
