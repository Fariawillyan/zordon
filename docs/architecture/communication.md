---
document: architecture-communication
module: architecture
section: communication
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [comunicacao,processos,transporte,fronteiras,ipc]
specId: null
---

# Comunicação entre componentes

## 1. Objetivo

Mapear todos os canais de comunicação do Zordon, quem fala com quem, e por quê
cada escolha de transporte.

## 2. Mapa

```text
 WINDOWS                                    │  WSL2
 ───────────────────────────────────────────│──────────────────────────────
                                            │
  zordon-desktop ──┐                        │
   (JavaFX)        │                        │
                   ├── ZWP / WebSocket ─────┼──► zordon-core ──┐
  zordon-host ─────┘   ws://…:8777/zwp/v1   │                   │
   (headless)          JSON-RPC 2.0 +       │                   │ Unix socket
                       frames binários      │                   ▼
                                            │            zordon-voice
                                            │            (sidecar Python)
                                            │
                       arquivo de endpoint  │
  %USERPROFILE%\.zordon\endpoint.json ◄─────┼─── escrito pelo núcleo
```

| Canal | Transporte | Formato | Por quê |
|---|---|---|---|
| Cliente ↔ núcleo | WebSocket | JSON-RPC 2.0 + binário | [ADR-0003](../adr/ADR-0003-websocket-json-rpc.md) |
| Núcleo ↔ sidecar de voz | Socket Unix | JSON por linha + PCM | Local, baixa latência, sem porta |
| Núcleo → Windows | **O mesmo WebSocket, invertido** | JSON-RPC 2.0 | Firewall bloqueia entrada do WSL ([§R4](windows-wsl.md#r4--firewall-do-windows-bloqueia-wsl--windows)) |
| Descoberta e autenticação | Arquivo | JSON | [ADR-0006](../adr/ADR-0006-descoberta-por-arquivo-de-endpoint.md) |
| Núcleo ↔ MCP | stdio ou HTTP/SSE | JSON-RPC 2.0 | Padrão MCP; stdio não abre porta |

## 3. A decisão que molda tudo

**Só existe um listener, e ele fica no WSL.**

`zordon-desktop` e `zordon-host` são sempre clientes: eles abrem a conexão e a
mantêm. Toda comunicação do núcleo para o Windows — inclusive as chamadas do
`WindowsBridge` — viaja como requisição **no sentido inverso da mesma conexão**.

Isso resolve de uma vez:

- o firewall do Windows bloqueando entrada vinda do WSL;
- o IP variável do WSL2 em modo NAT;
- o `WSL_INTEROP` indisponível sob systemd;
- o caso do usuário com VPN corporativa, onde nenhuma regra de firewall
  sobreviveria.

Consequência de projeto: o `WindowsBridge` no núcleo não "chama o Windows"; ele
publica uma requisição na sessão ZWP do host conectado. Sem host conectado, a
operação falha imediatamente com `ERR_BRIDGE_UNAVAILABLE` em vez de travar.

## 4. Fronteiras de confiança

```text
   zordon-desktop        cliente autenticado, capacidades de UI
   zordon-host           cliente autenticado, capacidades de bridge e áudio
   zordon-core           autoridade
   zordon-voice          filho do núcleo, sem lógica de produto
   servidores MCP        UNTRUSTED — processo de terceiro
```

Um cliente `desktop` não pode se registrar como provedor de `WindowsBridge`; um
`host` pode. A capacidade é declarada no handshake e verificada a cada chamada.

Autenticação: token de 256 bits, novo a cada inicialização, do arquivo de
endpoint, no cabeçalho `Authorization`. Mais rejeição de qualquer handshake com
`Origin` — navegadores sempre o enviam, clientes nativos não.

## 5. Contratos

| Contrato | Documento |
|---|---|
| Protocolo, métodos, eventos, frames binários | [ZWP](../api/zwp-protocol.md) |
| Interfaces internas do núcleo | [Interfaces](../api/core-interfaces.md) |
| Modelo de eventos | [Orientação a eventos](event-driven.md) |
| Sidecar de voz | [Voz §7](../specs/voice/design.md#7-sidecar-zordon-voice) |

## 6. Riscos

| Risco | Mitigação |
|---|---|
| Porta alcançável por navegador | Token em cabeçalho + rejeição de `Origin` ([§R12](windows-wsl.md#r12--qualquer-coisa-no-pc-pode-falar-com-a-porta-do-núcleo)) |
| Endereço do WSL muda | Arquivo de endpoint + observação de mudança |
| Host ausente quebra funcionalidade | Falha rápida e degradação declarada, nunca espera indefinida |
| Frame binário em stream errado | Byte mágico `0x5A` falha alto |

## 7. Critérios de aceite

- `CA-1` Nenhum processo Windows abre porta de escuta.
- `CA-2` Handshake com `Origin` é rejeitado com código `4403`.
- `CA-3` Sem host conectado, operação de bridge falha em menos de 100 ms.
- `CA-4` Reinício do núcleo faz os clientes reconectarem sem intervenção.
