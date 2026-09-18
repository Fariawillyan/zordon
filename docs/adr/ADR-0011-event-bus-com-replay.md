---
document: adr-0011
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,event,bus,com,replay]
specId: null
---

# ADR-0011 — Event bus com sequência, políticas de fila e replay

**Status:** Aceito · 2026-09-17

## Contexto

O briefing pediu arquitetura orientada a eventos com um `ZordonEventBus`. Um
barramento ingênuo (lista de listeners chamados na thread do produtor) cria três
problemas em um sistema residente:

1. **Assinante lento bloqueia o produtor.** Uma UI travada atrasaria a execução
   de ferramentas.
2. **Rajadas afogam a UI.** Métricas a 1 Hz, `VOICE_LEVEL` a 20 Hz, tokens de
   streaming — centenas de eventos por segundo em picos.
3. **Reconexão perde eventos.** A UI que reconecta não sabe o que aconteceu no
   intervalo, e mostra estado errado sem perceber.

## Decisão

Barramento assíncrono com quatro propriedades:

**1. Publicar nunca bloqueia.** Cada assinante tem fila própria e delimitada. O
produtor entrega à fila e segue. Não existe política `BLOCK_PRODUCER`.

**2. Política de transbordo por tópico.**

| Tópico | Política | Razão |
|---|---|---|
| `permission` | `REJECT_PUBLISH` | Descartar pedido de autorização em silêncio é inaceitável. Se a fila enche, algo está muito errado e deve falhar alto |
| `chat`, `agents`, `tools` | `DROP_OLDEST` + marcador `gap` | O usuário perde histórico intermediário, mas sabe que perdeu |
| `SYSTEM_METRICS`, `VOICE_LEVEL` | `COALESCE` | O último valor substitui o pendente do mesmo recurso. Enfileirar 500 medidas de CPU não tem sentido |

**3. Número de sequência monotônico por inicialização do núcleo.** Cada evento
tem `seq` e um `startId` que identifica a execução. Anel dos últimos 2.000 eventos
ou 5 minutos, o que for menor.

**4. Replay na reconexão.**

| Situação | Comportamento |
|---|---|
| `startId` igual, `seq` dentro do anel | Reenvia a partir de `lastEventSeq + 1` |
| `startId` igual, `seq` velho demais | `resumed: false` — cliente refaz snapshot |
| `startId` diferente | O núcleo reiniciou — `resumed: false` + `CORE_STARTED` |

**O cliente nunca assume continuidade.** Ao receber `resumed: false`, descarta o
estado volátil e recarrega via `chat.history`, `system.metrics`, `mcp.list`,
`agent.list`. É essa disciplina que impede a classe de bugs "a UI mostra um
container que não existe mais".

**5. Assinatura por tópico, não por evento.** A tela de Chat assina
`chat,voice,permission`; a de Diagnostics assina tudo. Não se manda métrica a 1 Hz
para uma UI mostrando outra coisa.

## Alternativas descartadas

**Barramento síncrono.** Simples, e o acoplamento de latência entre UI e execução
o inviabiliza.

**Broker externo (Redis, NATS).** Resolve tudo e adiciona um serviço crítico a um
assistente pessoal. Desproporcional.

**Log persistente de eventos (event sourcing).** Replay ilimitado e auditoria
perfeita, mas escrita constante em disco (contra o requisito de <1 MB/min em
repouso) para um benefício que o anel em memória já entrega. A auditoria, que
precisa mesmo ser durável, é um subsistema separado com suas próprias garantias.

## Consequências

**Positivas.** Execução desacoplada da apresentação. Reconexão correta por
construção, não por sorte. Backpressure explícito e observável
(`zordon.eventbus.dropped` por tópico). Assinatura por tópico reduz tráfego.

**Negativas.** Mais complexo que uma lista de listeners. Replay limitado a 5
minutos — quem fica desconectado mais tempo refaz snapshot. Memória do anel
(~2.000 eventos ≈ poucos MB).

**Consequência para o lado do cliente.** Eventos entram por uma fila e são
aplicados à UI em lote, com teto de 30 atualizações por segundo
([UI §4](../specs/ui/design.md#4-regras-de-threading)). Sem o lote, uma
rajada de replay gera centenas de `Platform.runLater` e congela a janela — o
barramento resolve o lado do núcleo, e o cliente precisa fazer a sua parte.
