---
document: architecture-event-driven
module: architecture
section: event-driven
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [eventos,barramento,backpressure,replay,topicos]
specId: null
---

# Arquitetura orientada a eventos

## 1. Objetivo

Descrever o modelo de eventos do Zordon: o barramento interno, as políticas de
fila e as garantias de entrega.

## 2. Contexto

Tudo o que acontece no Zordon é um evento: a voz começou, um agente terminou, uma
ferramenta falhou, a CPU mudou, um detector achou algo. A UI, a auditoria, as
automações e as métricas são todas **consumidoras do mesmo fluxo** — o log de
atividades não é uma funcionalidade separada, é uma projeção.

O `ZordonEventBus` é interno ao núcleo; o [ZWP](../api/zwp-protocol.md) é a
projeção dele para clientes.

## 3. Topologia

```text
   produtores                  EventBus                 consumidores
 ┌──────────────┐                                    ┌───────────────┐
 │ Orchestrator │──┐                              ┌─►│ ZwpServer     │──► UI
 │ ToolRegistry │──┤     ┌──────────────────┐     ├─►│ AuditLog      │──► SQLite
 │ McpManager   │──┼────►│ fila delimitada  │─────┼─►│ Metrics       │
 │ Monitors     │──┤     │ por assinante    │     ├─►│ AutomationEng │
 │ Voice        │──┤     │ + nº de sequência│     ├─►│ DetectionEng  │
 │ Automation   │──┤     └──────────────────┘     └─►│ Logger        │
 │ DefenseEngine│──┘                                 └───────────────┘
 └──────────────┘
```

## 4. Invariantes

1. **Publicar nunca bloqueia.** Cada assinante tem fila própria e delimitada. Não
   existe política `BLOCK_PRODUCER`. Uma UI travada não pode atrasar a execução de
   uma ferramenta.
2. **Assinante lento não contamina os outros.** Filas independentes.
3. **Todo evento tem `seq` monotônico por inicialização do núcleo**, mais um
   `startId`. É o que permite reconexão correta.
4. **Eventos são imutáveis** (`record`) e serializáveis sem estado externo.
5. **Só o núcleo publica.** Nenhum módulo de capacidade publica direto; passa pela
   fachada, que garante `seq` e auditoria.

## 5. Tópicos

| Tópico | Conteúdo | Volume típico |
|---|---|---|
| `chat` | Turnos, streaming de resposta | Médio, em rajada |
| `voice` | Wake word, VAD, transcrição, nível de áudio | Alto (`VOICE_LEVEL` a ~20 Hz) |
| `tools` | Início e fim de chamada de ferramenta | Médio |
| `agents` | Ciclo de vida e progresso de agentes | Baixo |
| `mcp` | Conexão e desconexão de servidores | Baixo |
| `permission` | Pedidos e decisões | Baixo, crítico |
| `security` | Achados, contenções, disjuntores, lockdown | Baixo, crítico |
| `system` | Métricas, alertas, salto de relógio | Alto (1 Hz) |
| `automation` | Disparos e resultados | Baixo |
| `memory` | Fatos gravados | Baixo |
| `rag` | Indexação e recuperação | Baixo |
| `change` | Planos, aprovações e alterações de projeto | Baixo, crítico |

Catálogo completo de eventos em [ZWP §6](../api/zwp-protocol.md#6-eventos).

## 6. Política de transbordo

| Tópico | Política | Razão |
|---|---|---|
| `permission` | `REJECT_PUBLISH` | Descartar pedido de autorização em silêncio é inaceitável. Fila cheia significa que algo está muito errado — falhar alto é melhor |
| `security` | `REJECT_PUBLISH` + fila durável | Mesma razão, mais a garantia de entrega de [Comunicação §8](../security/communication.md#8-entrega-garantida) |
| `change` | `REJECT_PUBLISH` + fila durável | Alteração de projeto silenciosa é o que [ADR-0024](../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md) existe para impedir |
| `chat`, `agents`, `tools` | `DROP_OLDEST` + marcador `gap` | Perde-se histórico intermediário, mas o consumidor sabe que perdeu |
| `system` (métricas), `VOICE_LEVEL` | `COALESCE` | O último valor substitui o pendente do mesmo recurso. Enfileirar 500 medidas de CPU não tem sentido |
| demais | `DROP_OLDEST` | |

## 7. Assinatura e replay

Clientes assinam **tópicos**, não eventos individuais — a tela de Chat não
precisa de métricas a 1 Hz.

Exceção: **`security` e `change` não podem ser desassinados**. Um cliente que recusasse
eventos de segurança quebraria a invariante de
[ADR-0014](../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md), então
`session.unsubscribe` o rejeita.

Replay: anel dos últimos 2.000 eventos ou 5 minutos. Detalhes e semântica de
reconexão em [ZWP §8](../api/zwp-protocol.md#8-reconexão-e-replay) e
[ADR-0011](../adr/ADR-0011-event-bus-com-replay.md).

## 8. Do lado do consumidor

Um barramento correto não basta se o cliente processa mal. Regra para a UI
([UI §4](../specs/ui/design.md#4-regras-de-threading)): eventos entram em fila e
são aplicados **em lote**, com teto de 30 atualizações por segundo. Sem o lote,
uma rajada de replay gera centenas de `Platform.runLater` e congela a janela.

## 9. Observabilidade

| Métrica | Sinaliza |
|---|---|
| `zordon.eventbus.queue_depth` por tópico | Assinante lento |
| `zordon.eventbus.dropped` por tópico | Perda real de evento |
| `zordon.eventbus.publish_latency` | Contenção no barramento |

`dropped` em `permission`, `security` ou `change` deve ser sempre zero — se não for, é bug
de severidade máxima.

## 10. Riscos

| Risco | Mitigação |
|---|---|
| Assinante lento trava produtores | Filas independentes, sem `BLOCK_PRODUCER` |
| Rajada afoga a UI | `COALESCE` + lote no cliente |
| Reconexão com estado inconsistente | `startId` + `seq`; cliente nunca assume continuidade |
| Evento de segurança perdido | `REJECT_PUBLISH` + fila durável |

## 11. Critérios de aceite

- `CA-1` Publicar nunca bloqueia o produtor, sob nenhuma carga.
- `CA-2` `dropped` em `permission` e `security` é sempre zero.
- `CA-3` Um cliente não consegue desassinar `security`.
- `CA-4` Após reconexão com `startId` diferente, o cliente descarta estado volátil.
