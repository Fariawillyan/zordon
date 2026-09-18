---
document: adr-0025
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,modulo,transporte,zwp,codec]
specId: null
---

# ADR-0025 — Módulo de transporte `zordon-zwp`, separado do contrato

**Status:** Aceito · 2026-09-17

## Contexto

O mapa de módulos ([Componentes §2](../architecture/components.md#2-mapa-de-módulos))
define `zordon-api` como o contrato compartilhado entre Windows e WSL, com
**dependência nenhuma além do JDK** — regra 1 do ArchUnit, verificada no build.

Ao implementar o M0 ([SPEC-002](../specs/core/SPEC-002-fundacao-zwp-e-nucleo.md)),
essa regra encontrou um problema concreto. Três peças precisam do mesmo código de
transporte:

- `zordon-core` serve o ZWP;
- `zordon-desktop` o consome;
- `zordon-host` o consumirá no M2.

E o desktop não pode depender do núcleo (regra 3). Então o codec JSON-RPC, a
leitura e escrita do `endpoint.json`, o cliente e a política de reconexão não têm
onde morar: `zordon-api` não pode importar Jackson, e `zordon-core` é proibido
para os clientes.

## Alternativas

**A. Permitir Jackson em `zordon-api`.** Uma linha de mudança na regra 1. É o que
muitos projetos fazem, e Jackson é maduro e leve. Mas transforma o contrato em um
contrato **com opinião sobre serialização**: os dois lados passam a herdar a
versão de Jackson do outro, e um conflito de versão no cliente vira um problema do
núcleo. A regra existia justamente para impedir isso.

**B. Escrever um leitor e escritor de JSON à mão dentro de `zordon-api`.** Mantém
a regra intacta ao custo de reimplementar JSON — mais código, mais bugs, e um
lugar a menos onde alguém pensa "isso já existe pronto e testado".

**C. Duplicar o codec em `zordon-core` e em `zordon-desktop`.** Cada lado com a sua
cópia. Duplicação de **contrato**, que é a pior espécie: as duas cópias divergem
em silêncio e a divergência aparece como um bug de protocolo em produção. Os
[padrões §4](../process/code-standards.md#4-code-smells) tratam duplicação em três
lugares como bloqueante, e esta seria em dois lugares críticos.

**D. Um módulo de transporte entre o contrato e quem o usa.** `zordon-api`
continua com dependência zero; `zordon-zwp` depende dele, de Jackson e de uma
biblioteca de WebSocket; núcleo, desktop e host dependem de `zordon-zwp`.

## Decisão

**Alternativa D.** Cria-se `zordon-zwp` com o codec, o `endpoint.json`, o
`ZwpClient`, o `CoreConnection` e o `ReconnectBackoff`.

A fronteira é nítida e fácil de defender em revisão:

```text
zordon-api    o QUE viaja     records, enums, identificadores      dependência: nenhuma
zordon-zwp    COMO viaja      codec, socket, descoberta, backoff   dependência: JSON + WebSocket
```

O `ZwpServer` **não** vai para cá: ele é o único listener do sistema e pertence ao
núcleo, como o mapa de módulos sempre disse.

Consequência para a regra 3 do ArchUnit: `zordon-desktop` e `zordon-host` passam a
poder depender de `zordon-api`, `zordon-zwp` e `zordon-windows-bridge` — e
continuam proibidos de depender de `zordon-core` e de qualquer módulo de
capacidade. A regra ficou mais precisa, não mais frouxa.

## Consequências

**Positivas.** O contrato continua adotável por qualquer cliente futuro — uma CLI,
um teste, um cliente de outra linguagem que só precise dos formatos — sem herdar
escolha de serializador. A lógica de reconexão existe uma vez só e tem teste
próprio. O teste de contrato com amostras douradas fica onde o codec está, que é
onde a quebra aconteceria.

**Negativas.** Um módulo a mais para navegar, e uma decisão a mais na cabeça de
quem for adicionar algo: "isto é contrato ou é transporte?". A pergunta tem
resposta objetiva — se depende de Jackson ou de socket, é transporte — mas é uma
pergunta a mais.

**Alternativa se esta decisão se mostrar errada:** fundir `zordon-zwp` em
`zordon-api` é mecânico (mover pacotes e afrouxar a regra 1). O caminho de volta
custa uma tarde.
