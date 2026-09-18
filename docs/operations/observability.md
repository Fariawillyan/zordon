---
document: ops-observability
module: operations
section: observability
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [logs,metricas,tracing,diagnostics]
specId: null
---

# Operação — observabilidade

## 1. Por que isso não é opcional

Um assistente residente falha de formas que o usuário não consegue diagnosticar:
"ficou lento", "não entendeu", "não fez nada". Sem instrumentação, cada uma
dessas reclamações vira uma sessão de arqueologia. Com instrumentação, viram uma
tela.

Três perguntas que a observabilidade precisa responder em segundos:

1. Por que este turno demorou tanto?
2. Por que este turno custou tanto?
3. Por que o Zordon não fez o que eu pedi?

## 2. Logs

Estruturados em JSON desde o primeiro commit. SLF4J + Logback com encoder JSON.

```json
{"ts":"2026-09-17T22:31:05.142Z","level":"INFO","logger":"McpManager",
 "msg":"tool invoked","turnId":"t_91a","agentId":"system","runId":"r_22",
 "tool":"mcp:docker.listContainers","durationMs":142,"ok":true}
```

Campos de correlação obrigatórios quando existirem: `turnId`, `agentId`,
`runId`, `callId`. É o que permite reconstruir um turno inteiro com um `grep`.

| Destino | Conteúdo | Retenção |
|---|---|---|
| `~/.zordon/logs/core.jsonl` | Tudo em INFO+ | 7 dias, rotação diária, 100 MB de teto |
| `~/.zordon/logs/audit.db` | Auditoria (SQLite) | 180 dias |
| stdout | O mesmo de `core.jsonl` | Capturado pelo journald |

**Todo log passa por `SecretManager.redact`** antes de ser escrito. Sem exceção.

Níveis: `DEBUG` para fluxo interno, `INFO` para o que o usuário reconheceria como
um acontecimento, `WARN` para degradação, `ERROR` para falha que exige ação.
Se um `ERROR` aparece em operação normal, ou ele não é erro, ou há um bug.

## 3. Métricas

Micrometer no núcleo, sem backend obrigatório. Exposição opcional em
`/metrics` (Prometheus) desabilitada por padrão.

### IA

| Métrica | Tipo | Rótulos |
|---|---|---|
| `zordon.ai.requests` | contador | provider, model, role, outcome |
| `zordon.ai.latency.first_token` | histograma | provider, model |
| `zordon.ai.latency.total` | histograma | provider, model |
| `zordon.ai.tokens.input` | contador | provider, model, cached |
| `zordon.ai.tokens.output` | contador | provider, model |
| `zordon.ai.cost` | contador | provider, model |
| `zordon.ai.cache.hit_ratio` | medidor | provider, model |

`zordon.ai.cache.hit_ratio` é a métrica que mais paga: se ela cai, alguém
introduziu um invalidador no prefixo do prompt
([Core §3](../specs/core/design.md#3-composição-de-contexto)) e o custo subiu
silenciosamente.

### Ferramentas, agentes, MCP

| Métrica | Tipo | Rótulos |
|---|---|---|
| `zordon.tool.calls` | contador | tool, source, outcome, risk |
| `zordon.tool.duration` | histograma | tool, source |
| `zordon.tool.offered` | contador | tool — **quantas vezes foi oferecida ao modelo** |
| `zordon.agent.runs` | contador | agent, reason |
| `zordon.agent.steps` | histograma | agent |
| `zordon.agent.duration` | histograma | agent |
| `zordon.mcp.connected` | medidor | server |
| `zordon.mcp.errors` | contador | server, kind |

`zordon.tool.offered` comparado com `zordon.tool.calls` responde à pergunta 3 da
§1: se uma ferramenta nunca é oferecida, o problema é a seleção semântica; se
é oferecida e nunca chamada, o problema é a descrição dela.

### Voz, permissão, sistema

| Métrica | Tipo | Rótulos |
|---|---|---|
| `zordon.voice.wake_detections` | contador | outcome (command, timeout, cancelled) |
| `zordon.voice.stt.duration` | histograma | device (cpu/gpu) |
| `zordon.voice.stt.confidence` | histograma | |
| `zordon.voice.pipeline.latency` | histograma | stage |
| `zordon.permission.decisions` | contador | risk, decision, decided_by |
| `zordon.permission.prompt_latency` | histograma | — tempo até o usuário decidir |
| `zordon.eventbus.queue_depth` | medidor | topic |
| `zordon.eventbus.dropped` | contador | topic |
| `zordon.zwp.connections` | medidor | client_kind |

### Defesa e comunicação

| Métrica | Tipo | Rótulos |
|---|---|---|
| `zordon.detect.signals` | contador | detector, severity |
| `zordon.detect.findings` | contador | detector, severity, outcome |
| `zordon.detect.false_positive` | contador | detector — marcado pelo usuário |
| `zordon.defense.actions` | contador | action, authorization, reversible |
| `zordon.defense.containment_latency` | histograma | detector |
| `zordon.breaker.opened` | contador | subject_kind, reason |
| `zordon.lockdown.entered` | contador | trigger, auto |
| `zordon.quarantine.items` | medidor | — |
| `zordon.notify.published` | contador | severity, channel |
| `zordon.notify.containment_gap` | histograma | — **teto duro de 2 s** |
| `zordon.notify.time_to_acknowledge` | histograma | severity |
| `zordon.notify.dismissed_without_reading` | contador | severity |
| `zordon.notify.undelivered` | medidor | severity — pendentes na fila |

Três destas merecem atenção especial:

- **`zordon.notify.containment_gap`** mede o intervalo entre conter e avisar.
  O teto de 2 s é um requisito, não uma meta. Estourar é bug de severidade alta.
- **`zordon.notify.dismissed_without_reading`** é o termômetro da fadiga de
  alerta. Se sobe, o sistema está gritando demais e a política de severidade
  precisa mudar — nunca o usuário
  ([Comunicação §7](../security/communication.md#7-anti-fadiga)).
- **`zordon.detect.false_positive`** por detector é o que permite ajustar
  limiares com dado em vez de com impressão. Um detector com taxa alta é
  desligado até ser corrigido: ruído constante treina o usuário a ignorar tudo.

`zordon.voice.wake_detections{outcome=timeout}` é o contador de falsos positivos
da wake word. Se subir, a sensibilidade precisa de ajuste — e é assim que o
usuário descobre isso sem ter que desconfiar.

## 4. Tracing

Não é OpenTelemetry completo; é um modelo de span próprio, suficiente e barato,
persistido no SQLite por 7 dias.

```text
turn t_91a ─────────────────────────────────────────── 2.410 ms
├── stt                                                   380 ms
├── route                                                   5 ms
├── agent system r_22 ──────────────────────────────────  1.780 ms
│   ├── context.build                                       62 ms
│   │   ├── memory.recall                                   38 ms
│   │   └── tools.select                                    18 ms
│   ├── ai.request #1 ───────────────────────────────────   820 ms
│   │   └── first_token                                     640 ms
│   ├── tool mcp:docker.listContainers                      142 ms
│   │   └── permission.evaluate                               1 ms
│   └── ai.request #2 ───────────────────────────────────   740 ms
└── tts.first_phoneme                                       245 ms
```

Essa árvore é a resposta visual para "por que demorou". Ela aparece na tela de
Diagnostics para o último turno e para qualquer turno selecionado nos Logs.

## 5. Tela `Zordon > Diagnostics`

```text
┌───────────────────────────────────────────────────────────────────┐
│ DIAGNOSTICS                                          últimas 24 h │
├───────────────────────────────────────────────────────────────────┤
│ SAÚDE                                                             │
│  Núcleo       ● online   3d 4h        Voz       ● pronta          │
│  Host         ● conectado             MCP       ● 6/7             │
│  Provider IA  ● anthropic             Memória   ● 1.284 fatos     │
│  Auditoria    ● cadeia íntegra        Banco     ● 42 MB           │
│  Defesa       ● 24 detectores         Lockdown  ○ inativo         │
│  Disjuntores  ● 0 abertos             Defender  ● ativo (externo) │
│  Notificações ● 0 pendentes           Quarentena  3 itens         │
├───────────────────────────────────────────────────────────────────┤
│ DESEMPENHO                        p50      p95      alvo p50      │
│  Wake → feedback                  210ms    340ms    250ms    ✓    │
│  Fala → transcrição               620ms    1,4s     700ms    ✓    │
│  Fala → primeiro áudio            1,9s     3,8s     1,8s     ⚠    │
│  Primeiro token (texto)           740ms    1,9s     800ms    ✓    │
├───────────────────────────────────────────────────────────────────┤
│ CUSTO                                                             │
│  Hoje         US$ 1,84  de 10,00   ████░░░░░░░░░░░░░░             │
│  Mês          US$ 23,10 de 100,00  ████░░░░░░░░░░░░░░             │
│  Cache        68% de acerto        (economia estimada US$ 4,20)   │
│  Por modelo   opus-5 US$ 1,62 · haiku-4-5 US$ 0,22                │
├───────────────────────────────────────────────────────────────────┤
│ USO                                                               │
│  Turnos 84 (voz 61 · texto 23)      Wake words 73 (12 descartadas)│
│  Ferramentas 217                    Permissões 14 (12 ✓ · 2 ✗)   │
│  Achados 6 (🔴1 🟠2 🟡3)            Contenções 3 (todas revertíveis)│
│  Gap contenção→aviso  p50 340ms  p95 890ms  teto 2s        ✓     │
│  Agentes  system 38 · developer 21 · zordon 19 · research 6       │
├───────────────────────────────────────────────────────────────────┤
│ TOP FERRAMENTAS          chamadas    p50     erros                │
│  mcp:docker.listContainers    52    140ms      0                  │
│  skill:files.read             41     12ms      1                  │
│  skill:dev.gitStatus          33     85ms      0                  │
├───────────────────────────────────────────────────────────────────┤
│ ÚLTIMO TURNO  t_91a                             [ver árvore]      │
│ ERROS RECENTES (3)                              [ver]             │
└───────────────────────────────────────────────────────────────────┘
```

A coluna "alvo" ao lado de cada percentil vem direto de
[Visão §5](../vision.md#latência). Um requisito não-funcional que não é
medido contra o alvo é uma opinião.

## 6. Saúde

`system.health` devolve o estado por subsistema, usado pelo tray, pelo supervisor
e pela tela:

```json
{ "status": "degraded",
  "subsystems": {
    "ai":       { "status": "ok",      "provider": "anthropic" },
    "voice":    { "status": "ok",      "device": "cuda" },
    "host":     { "status": "ok",      "connectedSince": "..." },
    "mcp":      { "status": "degraded","connected": 6, "total": 7,
                  "failing": ["browser"] },
    "memory":   { "status": "ok",      "facts": 1284, "sizeMb": 42 },
    "audit":    { "status": "ok",      "chainVerified": true },
    "automation":{"status": "ok",      "active": 4 },
    "defense":  { "status": "ok",      "detectors": 24, "breakersOpen": 0,
                  "lockdown": false, "quarantined": 3,
                  "externalAv": { "defender": "active" } },
    "notify":   { "status": "ok",      "pending": 0, "undeliveredCritical": 0 }
  }
}
```

`degraded` quando qualquer subsistema não-crítico está fora; `down` quando IA ou
memória estão fora. **`defense` fora coloca o sistema em `degraded` e gera
notificação HIGH** — o usuário precisa saber que está sem a camada de detecção,
porque a ausência dela é silenciosa por natureza.

`undeliveredCritical > 0` é o indicador mais importante deste bloco: significa que
existe uma ação autônoma que o usuário ainda não viu.

`externalAv` reporta o estado do Windows Defender. Se ele estiver desativado, o
Zordon avisa e **não finge cobrir a lacuna**
([ADR-0017](../adr/ADR-0017-zordon-nao-e-antivirus.md)). O tray fica âmbar em `degraded` — o usuário precisa saber que
está rodando com menos capacidade antes de concluir que o Zordon "ficou burro".

## 7. Comportamento com erro

- Toda exceção não tratada no núcleo é capturada, logada com contexto e vira
  `SYSTEM_ALERT`. O núcleo **não morre** por exceção em turno.
- O último crash é serializado em `~/.zordon/last-crash.json` e exibido ao
  reconectar.
- Três erros do mesmo tipo em 5 minutos geram alerta agregado, não três alertas.
- Erro exibido ao usuário é em português, com o que ele pode fazer a respeito. O
  detalhe técnico fica atrás de "ver detalhes" e é copiável.

## 8. O que não medimos

Deliberadamente fora, por privacidade:

- Conteúdo de conversa em métrica ou telemetria.
- Qualquer telemetria que saia da máquina. Não existe *phone home*.
- Áudio, nem em amostra, nem para depuração. Depurar wake word usa gravações que
  o usuário inicia explicitamente e que ficam em disco sob controle dele.
