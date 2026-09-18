---
document: adr-0003
module: adr
section: decision
version: 1
updatedAt: 2026-09-17
securityLevel: public
tags: [adr,decisao,websocket,json,rpc]
specId: null
---

# ADR-0003 — JSON-RPC 2.0 sobre WebSocket como protocolo

**Status:** Aceito · 2026-09-17

## Contexto

O briefing deixou a escolha aberta entre WebSocket, HTTP e gRPC, pedindo
preferência por WebSocket ou gRPC streaming para tempo real.

Requisitos do transporte:

1. Streaming de eventos do núcleo para clientes (tokens, métricas, alertas).
2. Requisição/resposta em ambos os sentidos — o núcleo precisa chamar o Windows.
3. Áudio bidirecional de baixa latência.
4. Atravessar a fronteira Windows ↔ WSL2 sem abrir porta no lado Windows.
5. Depurável por um humano.
6. Permitir clientes futuros (CLI, web) sem esforço desproporcional.

O requisito 2 é o mais restritivo e é consequência de
[R4](../architecture/windows-wsl.md#r4--firewall-do-windows-bloqueia-wsl--windows):
o firewall do Windows bloqueia entrada vinda do WSL. Só existe uma porta escutando,
no WSL; tudo que o núcleo precisa do Windows tem que viajar pela conexão que o
cliente abriu.

## Alternativas

**A. HTTP + SSE.** Simples, mas unidirecional para eventos e exige uma segunda
conexão para comandos. Não resolve o requisito 2 sem long-polling. Áudio
bidirecional fica ruim.

**B. gRPC com streaming bidirecional.** Tipagem forte, codegen, HTTP/2, eficiente
em binário, streaming nativo nos dois sentidos. Mas: `grpc-java` traz uma árvore
de dependências considerável para o cliente JavaFX; o modelo de streaming
bidirecional resolve requisição/resposta inversa de forma desajeitada (é preciso
multiplexar correlação manualmente dentro do stream, reimplementando o que o
JSON-RPC já dá); depuração exige ferramentas específicas; e um cliente web
precisaria de gRPC-Web com proxy.

**C. JSON-RPC 2.0 sobre WebSocket, com frames binários para mídia.** Uma conexão,
uma porta. Bidirecionalidade simétrica é nativa do JSON-RPC (ambos os lados
mandam requisições com `id` próprio). Frames binários do WebSocket carregam áudio
sem base64. Depurável com qualquer ferramenta de WebSocket e legível a olho nu.
Cliente web é trivial. É a mesma forma de protocolo que o MCP usa, o que reduz a
carga conceitual de quem trabalha nos dois. Mas: JSON é mais verboso e mais caro
de serializar que protobuf, e não há tipagem forte automática entre as pontas.

## Decisão

**Alternativa C.**

O argumento decisivo é o requisito 2. gRPC é excelente quando a relação é
cliente-servidor; aqui a relação é peer-to-peer com um único socket, e JSON-RPC
modela isso diretamente enquanto gRPC exige reimplementar correlação por cima de
um stream.

Sobre o custo do JSON: o volume real de mensagens de controle é pequeno (dezenas
por turno). O que tem volume é áudio, e áudio não passa por JSON — vai em frames
binários. O ganho do protobuf se aplicaria justamente onde não usamos JSON.

A falta de tipagem entre pontas é mitigada por ambas serem Java e compartilharem
`zordon-api`, com testes de contrato sobre amostras douradas.

## Consequências

**Positivas.** Uma porta, uma conexão, um protocolo. Bidirecionalidade natural.
Depuração trivial. Cliente web ou CLI sem infraestrutura adicional. Frames
binários sem overhead de codificação. Alinhamento conceitual com MCP.

**Negativas.** Sem codegen; os records do `zordon-api` são escritos e mantidos à
mão. JSON custa mais CPU que protobuf (irrelevante neste volume). Controle de
fluxo para áudio precisa ser implementado por nós (crédito explícito), enquanto
o HTTP/2 do gRPC daria de graça.

**Se mudarmos de ideia**, o path `/zwp/v1` permite servir um `/zgrpc` em paralelo
sem quebrar clientes. A camada de aplicação não conhece o transporte.
