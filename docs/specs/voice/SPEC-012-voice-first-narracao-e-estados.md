---
document: spec-012
module: voice
section: spec
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [spec,voz,voice-first,narracao,estados,trace,observabilidade,modo-tecnico]
specId: SPEC-012
---

# SPEC-012 — Voice-first: narração, estados visuais e trace

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `CoreAgent` (intérprete, narrador, trace), `JavaFxAgent` (estados, modo técnico) |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M2 |
| **Supera** | SPEC-010 CA-3 (texto da pílula) e a exibição técnica padrão das SPECs 005 e 010 |

Pedida pelo owner em 2026-09-18, antes do motor de voz pesado
([SPEC-011](SPEC-011-motor-de-voz-ouvir-e-falar.md)), para que ele já nasça
falando pelo narrador.

> **Zordon é voice-first. A comunicação operacional com o usuário ocorre
> prioritariamente por voz e estados visuais; detalhes técnicos permanecem
> ocultos por padrão.** ([ADR-0029](../../adr/ADR-0029-voice-first.md))

## 1. Objetivo

O usuário saber o que o Zordon está fazendo ouvindo-o e olhando o núcleo, sem ler
nada — e poder ver tudo, em detalhe, quando pedir o modo técnico.

## 2. Problema

Hoje o estado aparece em texto (pílula, Logs, Diagnóstico, rodapé das respostas)
e nada é falado. Quando o M3 trouxer agentes e ferramentas, cada passo técnico
viraria uma linha na tela ou, pior, uma frase falada: "abrindo arquivo X",
"rodando comando Y" — cansativo e sem valor. Falar o raciocínio do modelo seria
ainda pior: confunde o que foi cogitado com o que foi feito.

## 3. Escopo

- **Intérprete de atividade** (núcleo): assina o barramento, agrupa eventos em
  etapas e publica o estado `ACTIVITY_STATE`.
- **Narrador de voz** (núcleo): decide o que falar a partir das etapas, com
  prioridade, janela de agrupamento, intervalo mínimo e sem repetição; publica
  `VOICE_NARRATION` e entrega ao TTS quando o motor da SPEC-011 existir.
- **Estados visuais** (desktop): dez estados, só animação.
- **Pílula sem texto**: microfone e desligar viram ícones, com texto acessível.
- **Modo técnico** (desktop): desligado por padrão; liga Painel, Logs,
  Diagnóstico e os metadados técnicos da conversa.
- **Live Trace** (núcleo): todo evento em JSONL diário, só acrescenta.

## 4. Não escopo

- A voz em si (TTS, reprodução): SPEC-011. Até lá o narrador publica o evento e o
  trace o registra; nada é falado.
- Eventos de agentes, ferramentas, permissão e alteração de projeto: chegam com o
  M3 em diante. A tabela do §7 já define como serão narrados; o intérprete só os
  trata quando existirem no catálogo.
- Auditoria de segurança à prova de adulteração: é do `zordon-security` (M3). O
  trace é observabilidade, não auditoria.

## 5. Arquitetura

```text
 Eventos (chat, voz, sistema; depois agentes, ferramentas, permissão)
      │
      ▼
 ZordonEventBus ─────────────────────────────► LiveTrace ~/.zordon/trace/AAAA-MM-DD.jsonl
      │
      ▼
 ActivityInterpreter  evento → etapa (Stage) + estado visual (ActivityState)
      │                         └──► ACTIVITY_STATE {state}
      ▼
 VoiceNarrator        etapa → frase? prioridade, agrupamento, repetição
      │                         └──► VOICE_NARRATION {text, priority, category}
      ▼
 NarrationSink        hoje: nenhum; SPEC-011: motor de voz → host
```

O intérprete e o narrador ignoram os próprios eventos (`ACTIVITY_STATE`,
`VOICE_NARRATION`) e `VOICE_LEVEL`: não há laço.

## 6. Fluxo

1. Usuário fala → `listening` → transcrição → `understanding`.
2. Turno com modelo → `planning`; demorando mais de 8 s → fala "Ainda estou
   trabalhando nisso." uma vez.
3. Resposta de um turno de voz → o narrador fala a resposta → `speaking` →
   `done` por 1,5 s → `idle`.
4. Erro → `error`, fala uma frase do tipo de erro.
5. Futuro: ferramenta → `executing`; vários agentes → `agents`; pedido de
   autorização → `attention` e fala "Preciso da sua autorização para continuar."

## 7. Interfaces

Estados (`ACTIVITY_STATE.state`):

| Estado | Quando | Animação |
|---|---|---|
| `idle` | Repouso | Parada |
| `listening` | Escuta ou teste do microfone | Anel respira com o nível do microfone |
| `understanding` | Transcrição, comando recebido | Partículas em espiral para o centro |
| `planning` | Modelo decidindo | Arcos girando devagar |
| `executing` | Ferramenta rodando (M3+) | Arcos rápidos, hachuras varrendo |
| `agents` | Mais de um agente trabalhando (M5+) | Satélites em órbita, um por agente |
| `speaking` | Narrador falando | Ondas pulsando |
| `done` | Resultado entregue | Anel se expande uma vez, e volta ao repouso |
| `attention` | Autorização, aviso | Âmbar, pulso lento |
| `error` | Falha | Vermelho, pulso |

Narração — só a partir de campos estruturados, nunca do texto de raciocínio:

| Evento | Etapa | Fala | Prioridade |
|---|---|---|---|
| `USER_COMMAND` | entendendo | — | — |
| `AI_THINKING` | planejando | — (8 s depois: "Ainda estou trabalhando nisso.") | normal |
| `AI_RESPONSE` final de turno de voz | resultado | a resposta, normalizada | alta |
| `AI_RESPONSE` final de turno digitado | resultado | — (a resposta já está na conversa) | — |
| `AI_ERROR` de turno de voz, `NO_CREDENTIALS`/`QUOTA_EXHAUSTED` | erro | "O provedor de IA não está configurado." / "O provedor de IA recusou por falta de crédito." | alta |
| `AI_ERROR` de turno de voz, outro | erro | "Não consegui responder agora." | alta |
| `AI_ERROR` de turno digitado | erro | — (o erro já está na conversa) | — |
| `VOICE_STATE` com `lastTest` novo | resultado | "Microfone funcionando." / "O sinal do microfone está baixo." / "Não ouvi nada no microfone." | normal |
| `SYSTEM_ALERT` técnico | — | — | — |
| Futuro `TOOL_STARTED` (agrupado por etapa) | executando | "Executando os testes." | normal |
| Futuro `AGENT_*` | agentes | "Codex está corrigindo e Claude está revisando." | normal |
| Futuro `PERMISSION_REQUESTED` | atenção | "Preciso da sua autorização para continuar." | autorização |

Regras do narrador:

- Janela de 2 s por etapa: várias mudanças de etapa na janela → fala só a última.
- Intervalo mínimo de 4 s entre falas normais; alta e autorização não esperam.
- A mesma frase não se repete em 30 s.
- Uma fala normal pendente é descartada quando chega alta ou autorização. A fila
  de reprodução (no máximo 3) e a interrupção da fala normal em curso por uma
  autorização são do motor de voz, que recebe as falas (SPEC-011).
- Nenhum caminho de arquivo, comando ou identificador técnico na fala.

Eventos novos no tópico `voice`: `ACTIVITY_STATE {state}`, `VOICE_NARRATION
{text, priority, category}`.

Trace: `~/.zordon/trace/AAAA-MM-DD.jsonl`, uma linha por evento: `{seq, ts, type,
topic, payload}`; lacunas marcadas com `{"gap": n}`. `system.diagnostics` ganha
`trace {dir, file}`.

## 8. Eventos

`ACTIVITY_STATE` e `VOICE_NARRATION` (novos); consome chat, voz e sistema.

## 9. Dados

Trace em disco, diretório 0700, arquivos 0600, só acrescenta, um arquivo por dia,
nunca apagado pelo Zordon ([ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md)).
Contém o texto das conversas (é o registro completo); não contém áudio nem
chaves, porque eventos não os carregam. `VOICE_LEVEL` fica fora: são dois números
20 vezes por segundo.

## 10. Segurança

- Microfone ligado continua evidente sem texto: ícone aceso, estado `listening`
  e botão de desligar a um clique.
- Autorização é falada e mostrada como `attention`; a decisão continua no
  diálogo de permissão (M3), nunca por voz em ação de risco
  ([Voz §6](design.md#6-privacidade-e-falsos-positivos)).
- O trace pode conter conversa: fica no perfil do usuário, com permissões
  restritas, e não sai da máquina.

## 11. Permissões

Nenhuma nova. O modo técnico é preferência de exibição, não privilégio.

## 12. Observabilidade

O Live Trace é a observabilidade. A tela de Logs (modo técnico) mostra o fluxo ao
vivo; o Diagnóstico aponta o arquivo do dia.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Rajada de eventos de etapa | Uma fala por janela; estado visual acompanha o último |
| Evento sem frase | Não é falado; fica no trace |
| Disco cheio ao gravar o trace | WARN no log do serviço; o núcleo segue |
| Motor de voz ausente | Narração publicada e registrada, não falada |

## 14. Testes

Intérprete e narrador puros, com relógio falso; trace com diretório temporário;
estados e modo técnico no desktop com e sem display.

### Evidências (2026-09-18)

- No serviço real, com um host de teste enviando um seno: o teste do microfone
  publicou `ACTIVITY_STATE` `listening` → `idle` e, depois da janela de 2 s, a
  narração "Microfone funcionando." (prioridade normal, categoria resultado).
- `~/.zordon/trace/2026-09-18.jsonl` (0600, diretório 0700): 11 linhas — início do
  núcleo, estados, narração e snapshots de voz; nenhum `VOICE_LEVEL`.
- Capturas de cada estado em `zordon-desktop/build/ui-snapshots/estado-*.png`; os dez
  geram imagens diferentes, e com movimento reduzido só a cor muda.
- Nada é falado ainda: o `NarrationSink` de produção é silencioso até o motor de
  voz da SPEC-011.

## 15. Critérios de aceite

- `CA-1` Dado cada evento da tabela do §7, então o intérprete produz a etapa e o
  estado indicados, e ignora `ACTIVITY_STATE`, `VOICE_NARRATION` e `VOICE_LEVEL`.
- `CA-2` Dado qualquer evento, então a fala vem só dos modelos de frase do §7:
  texto de raciocínio e fragmentos do modelo nunca viram fala, e evento sem
  modelo não é falado.
- `CA-3` Dadas várias mudanças de etapa em 2 s, então sai uma fala, a da última;
  entre falas normais há pelo menos 4 s; a mesma frase não se repete em 30 s; alta
  e autorização passam na frente e descartam a normal pendente.
- `CA-4` Dado um turno de voz, então a resposta é falada; dado um turno digitado,
  não.
- `CA-5` Dada uma narração, então `VOICE_NARRATION` traz texto, prioridade e
  categoria, e o texto não contém caminho, comando nem identificador de turno.
- `CA-6` Dado cada estado, então o núcleo visual tem uma animação própria e a tela
  de Voz não mostra texto operacional; com movimento reduzido, só a cor muda.
- `CA-7` Dada a pílula, então ela não tem texto visível: ícone de microfone e
  botão de desligar, com os rótulos da SPEC-006 como texto acessível.
- `CA-8` Dado o modo técnico desligado (padrão), então Painel, Logs, Diagnóstico e
  os metadados técnicos das respostas ficam ocultos; ligado, aparecem.
- `CA-9` Dado o núcleo no ar, então todo evento, menos `VOICE_LEVEL`, vai para o
  JSONL do dia, com permissões 0600, só acrescentando; o diagnóstico aponta o
  arquivo.

## 16. Impacto em outros módulos

- `zordon-api`: eventos `ACTIVITY_STATE`, `VOICE_NARRATION`.
- `zordon-core`: `ActivityInterpreter`, `VoiceNarrator`, `NarrationSink`,
  `LiveTrace`; diagnóstico.
- `zordon-desktop`: estados no `VoiceVisualizer`, pílula em ícones, modo técnico.
- SPEC-011: a fala sai pelo narrador (`NarrationSink`), não direto do turno.
- Documentação: [Visão §3](../../vision.md#3-princípios-de-design) (princípio 6),
  [Arquitetura §5](../../architecture/overview.md#5-arquitetura-orientada-a-eventos),
  [ZWP §6](../../api/zwp-protocol.md#catálogo).

## 17. Dependências

- [ADR-0029](../../adr/ADR-0029-voice-first.md) · [SPEC-006](SPEC-006-tela-e-estado-da-voz.md) ·
  [SPEC-009](SPEC-009-frames-de-audio-e-teste-do-microfone.md) ·
  [SPEC-010](../ui/SPEC-010-shell-compacto-centrado-na-voz.md) · [SPEC-011](SPEC-011-motor-de-voz-ouvir-e-falar.md)
