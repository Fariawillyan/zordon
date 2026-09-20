---
document: adr-0027
module: adr
section: decision
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [adr,decisao,wsl,host,supervisor,processo,agendador]
specId: null
---

# ADR-0027 — O supervisor do WSL é o agendador do Windows, não o host

**Status:** Aceito · 2026-09-18

## Contexto

[R1](../architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows) tem três
camadas. A segunda previa um supervisor dentro do `zordon-host`: sem conexão com
o núcleo, o host executaria `wsl.exe -d Ubuntu --exec /bin/true`, com backoff até
5 min, para religar a distro depois de um `wsl --shutdown`.

A regra 4 de [Componentes §4](../architecture/components.md#4-regras-de-dependência-verificadas-no-build)
diz que nenhuma classe fora de `zordon-security` executa processo, e o
[ADR-0007](ADR-0007-permissao-sobre-acao-estruturada.md) explica por quê: execução
de processo passa por um executor mediado. O host é justamente o componente que,
no M3, vai abrir aplicativos no Windows a pedido do núcleo. Se ele nascer com um
`ProcessBuilder` "só para o `wsl.exe`", a primeira exceção à regra existe antes
do mediador, e a segunda vai parecer natural.

## Alternativas

| Alternativa | Custo | Por que não |
|---|---|---|
| Host executa `wsl.exe`, com exceção nomeada no ArchUnit | Uma classe, uma exceção | Abre a regra 4 no único processo Windows que depois recebe pedidos de execução |
| Host chama `WslLaunch` (`wslapi.dll`) por código nativo | JNA ou FFM no host | É execução de processo com outro nome, e mais difícil de revisar |
| Adiar o supervisor | Nenhum | `wsl --shutdown` deixaria o Zordon fora até o próximo logon |
| **Tarefa agendada repete o comando** | Um `wsl.exe` curto a cada 5 min | — |

## Decisão

A tarefa `Zordon WSL Boot` ganha repetição a cada **5 min**, sem prazo, além do
disparo no logon, com `MultipleInstances=IgnoreNew`. Com a distro no ar, o
comando termina em milissegundos. Depois de um `wsl --shutdown`, a distro volta
em até 5 min, o mesmo teto de backoff que o supervisor teria.

O `zordon-host` não executa processo nenhum. A regra 4 passa a valer também no
host e é verificada por ArchUnit no próprio módulo.

## Consequências

- R1 continua com três camadas; a segunda muda de dono. O texto de R1 é
  atualizado.
- O host fica menor e revisável: rede, áudio e nada mais neste marco.
- O agendador do Windows religa a distro mesmo se o host estiver parado; o
  supervisor no host não religaria.
- Custo: um processo `wsl.exe` a cada 5 min enquanto o usuário estiver logado.
- Quando o M3 trouxer o executor mediado para o host, esta decisão pode ser
  revista. Mover o supervisor para lá passaria a ser uma chamada ao mediador, e
  não uma exceção.
