---
document: architecture-windows-wsl
module: architecture
section: windows-wsl
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [riscos,wsl2,rede,interop,systemd]
specId: null
---

# Arquitetura — Windows ↔ WSL: riscos e armadilhas

Este é o documento mais importante para ler antes de escrever código. A maior
parte do que mata projetos "assistente no WSL com UI no Windows" não é a IA — é
ciclo de vida de processo, rede e interop.

Todos os fatos marcados com **[medido]** foram verificados nesta máquina
(Windows 11 build 26100, WSL2 Ubuntu com systemd, modo de rede `nat`) em
2026-09-17. Os números podem variar em outra configuração, mas a ordem de
grandeza e a natureza do problema não.

## 1. Registro de riscos

| ID | Risco | Prob. | Impacto | Onde é tratado |
|----|-------|-------|---------|----------------|
| R1 | WSL não sobe no boot do Windows | **Alta** | Crítico | [§R1](#r1--o-wsl-não-sobe-no-boot-do-windows) · [Operação](../operations/install.md) |
| R2 | `wsl --shutdown` / Windows Update derruba o núcleo | Média | Alto | [§R2](#r2--o-wsl-é-derrubado-por-fora) |
| R3 | IP do WSL2 muda a cada boot | **Certa** | Alto | [§R3](#r3--o-ip-do-wsl2-muda-a-cada-boot) · [ADR-0006](../adr/ADR-0006-descoberta-por-arquivo-de-endpoint.md) |
| R4 | Firewall bloqueia WSL → Windows | **Alta** | Alto | [§R4](#r4--firewall-do-windows-bloqueia-wsl--windows) |
| R5 | `WSL_INTEROP` indisponível sob systemd | **Alta** | Alto | [§R5](#r5--wsl_interop-não-existe-dentro-de-um-serviço-systemd) |
| R6 | Sem dispositivo de áudio dentro do WSL | **Certa** | Alto | [§R6](#r6--não-há-áudio-confiável-dentro-do-wsl) · [ADR-0009](../adr/ADR-0009-captura-windows-inferencia-wsl.md) |
| R7 | Relógio do WSL desvia após suspensão | Média | Médio | [§R7](#r7--o-relógio-do-wsl-desvia-após-suspensão) |
| R8 | Tradução de caminhos Windows ↔ Linux | **Certa** | Médio | [§R8](#r8--caminhos-são-dois-mundos) |
| R9 | I/O em `/mnt/c` é lentíssimo | **Certa** | Médio | [§R9](#r9--io-em-mntc-é-ordens-de-grandeza-mais-lento) |
| R10 | `vmmem` consome RAM demais | Média | Médio | [§R10](#r10--vmmem-cresce-e-não-devolve) |
| R11 | Prompt injection via conteúdo de ferramenta | **Alta** | **Crítico** | [Segurança §6](../security/model.md#6-prompt-injection) |
| R12 | Porta do núcleo exposta a outros processos locais | Média | **Crítico** | [§R12](#r12--qualquer-coisa-no-pc-pode-falar-com-a-porta-do-núcleo) |
| R13 | Custo e latência de LLM sem teto | **Alta** | Médio | [Core §7](../specs/core/design.md#7-orçamento-e-custo) |
| R14 | Disputa de GPU com jogos/Unreal | Média | Médio | [§R14](#r14--a-gpu-é-disputada) |
| R15 | Segredos em texto plano | Média | **Crítico** | [Segurança §5](../security/model.md#5-segredos) |
| R16 | Falso positivo de wake word grava conversa | Média | Alto | [Voz §6](../specs/voice/design.md#6-privacidade-e-falsos-positivos) |
| R17 | Backpressure no event bus trava o núcleo | Média | Alto | [ADR-0011](../adr/ADR-0011-event-bus-com-replay.md) |
| R18 | MCP server instável trava o turno | **Alta** | Médio | [MCP §6](../specs/mcp/design.md#6-isolamento-de-falhas) |
| R19 | Migração de schema corrompe memória | Baixa | Alto | [Memória §7](../specs/memory/design.md#7-migrações) |
| R20 | Núcleo morre em silêncio e ninguém percebe | Média | Alto | [§R20](#r20--morte-silenciosa) |
| R21 | Usuário do Windows ≠ usuário do WSL | **Certa** | Médio | [§R21](#r21--o-usuário-do-windows-não-é-o-usuário-do-wsl) |
| R22 | Fadiga de alerta faz o usuário ignorar a defesa | **Alta** | Alto | [Comunicação §7](../security/communication.md#7-anti-fadiga) |
| R23 | Falso positivo contém algo legítimo | Média | Médio | [Defesa §5](../security/defense.md#5-defense-engine-e-playbooks) |
| R24 | Detecção estoura o orçamento de CPU em repouso | Média | Alto | [ADR-0017](../adr/ADR-0017-zordon-nao-e-antivirus.md) |
| R25 | Usuário acredita ter antivírus e não tem | Média | **Crítico** | [ADR-0017](../adr/ADR-0017-zordon-nao-e-antivirus.md) |
| R26 | Contribuição externa comprometida (cadeia de suprimentos) | Média | **Crítico** | [Cadeia de suprimentos](../security/supply-chain.md) |

---

## 2. Riscos de ciclo de vida

### R1 — O WSL não sobe no boot do Windows

**O problema.** O WSL2 não é um serviço do Windows. Ele é uma VM leve iniciada
sob demanda, quando algo a invoca (abrir o terminal, rodar `wsl.exe`, acessar
`\\wsl$`). Depois de um reboot do Windows, **nada** inicia o WSL sozinho. O
`systemd` dentro da distro só começa a existir depois que a VM sobe. Portanto
`zordon.service` com `WantedBy=multi-user.target` não é suficiente: ele sobe
quando o WSL sobe, e o WSL não sobe.

Este é o motivo número um pelo qual assistentes "residentes" no WSL não
funcionam depois de reiniciar o PC.

**Mitigação (obrigatória, três camadas):**

1. **Tarefa agendada no logon** que acorda a distro:
   ```powershell
   # roda oculto, sem janela, no logon do usuário
   wsl.exe -d Ubuntu --exec /bin/true
   ```
   Isso inicializa a VM; o systemd sobe; `zordon.service` sobe junto. O comando
   retorna em ~2 s e a VM permanece viva porque o systemd tem processos rodando.

2. **Supervisor pelo agendador do Windows.** A mesma tarefa repete o
   `wsl.exe --exec /bin/true` a cada 5 min, sem instâncias paralelas. Isso cobre
   o caso de o usuário ter dado `wsl --shutdown`: a distro volta em até 5 min,
   o mesmo teto de backoff que um supervisor teria. O `zordon-host` não executa
   processo nenhum ([ADR-0027](../adr/ADR-0027-supervisor-do-wsl-pelo-agendador.md)).

3. **Ociosidade desligada** no `.wslconfig`: `[general] instanceIdleTimeout=-1`
   e `[wsl2] vmIdleTimeout=-1`, para que o WSL não decida desligar a distro nem
   a VM por falta de terminal aberto.

   **[medido]** Em 2026-09-18, depois de reiniciar o Windows, a tarefa agendada
   subiu a distro e o núcleo ficou pronto em 15 s — e o WSL **desligou a distro 17 s
   depois**, levando o núcleo junto. O culpado é `instanceIdleTimeout` (padrão 15 s,
   seção `[general]`), e não `vmIdleTimeout` (padrão 60 s, seção `[wsl2]`), que só
   entra depois que a distro já caiu. Os dois precisam ser `-1`
   ([documentação da Microsoft](https://learn.microsoft.com/en-us/windows/wsl/wsl-config)).

**Teste de aceitação.** Reiniciar o Windows, não abrir nenhum terminal, esperar
60 s, dizer "Zordon". Se responder, R1 está resolvido. Este é o item 3 do
critério de MVP em [Visão §7](../vision.md#7-critério-de-sucesso-do-mvp).

### R2 — O WSL é derrubado por fora

`wsl --shutdown` (o usuário faz isso para liberar RAM), atualização do WSL pela
Microsoft Store, Windows Update, hibernação mal-comportada e crash do
`vmmem` derrubam a VM inteira sem aviso ao núcleo.

**Mitigação.**
- O supervisor do R1 cobre a religada.
- Todo estado que importa é durável **no momento em que é criado**, não no
  shutdown. Não existe "salvar ao sair" no Zordon — não há garantia de um sinal
  de saída.
- Automações agendadas gravam `last_fired_at`; ao subir, o `Scheduler` calcula
  disparos perdidos e aplica a política do job (`catch_up: skip | once | all`).
  Sem isso, um job de 10 min perdido por 6 h dispararia 36 vezes seguidas.
- O núcleo emite `CORE_STARTED` com `startId` novo. A UI usa isso para saber que
  o replay de eventos não é contínuo e limpar o estado volátil.

### R20 — Morte silenciosa

Um núcleo que morreu é indistinguível, para o usuário, de um núcleo que não tem
nada a dizer. O assistente simplesmente para de responder e ninguém nota até
precisar dele.

**Mitigação.**
- `Restart=always` com `RestartSec=3` e `StartLimitBurst` alto o suficiente para
  não cair em `failed` por crash-loop curto.
- Heartbeat ZWP a cada 10 s; sem 3 heartbeats, o cliente marca offline.
- O ícone do tray reflete o estado do núcleo em tempo real (verde/âmbar/vermelho),
  e o `zordon-host` dispara notificação nativa do Windows na transição para
  offline por mais de 60 s.
- `zordon-core` grava `~/.zordon/last-crash.json` com stack e contexto do último
  crash; a tela de Diagnostics mostra isso ao reconectar.

---

## 3. Riscos de rede

### R3 — O IP do WSL2 muda a cada boot

**[medido]** Nesta máquina o modo de rede é `nat` e a interface `eth0` tem
`172.19.x.x/20`. Esse endereço muda a cada inicialização da VM. Não existe
IP estável para o WSL em modo NAT.

O que funciona e o que não funciona em modo `nat`:

| Direção | Endereço | Funciona? |
|---|---|---|
| Windows → WSL | `localhost:PORTA` | Sim, via relay do WSL, **se** o serviço fizer bind em `0.0.0.0` |
| Windows → WSL | `127.0.0.1:PORTA` com bind em `127.0.0.1` dentro do WSL | **Não** |
| Windows → WSL | `<ip-do-wsl>:PORTA` | Sim, mas o IP muda |
| WSL → Windows | `localhost` | **Não** |
| WSL → Windows | IP do gateway (`/etc/resolv.conf`) | Sim, **se** o firewall permitir (R4) |
| WSL → Windows | `$(hostname).local` | Depende de mDNS; não confiar |

**Mitigação em duas frentes:**

1. **Preferir modo espelhado.** Windows 11 22H2+ com WSL 2.0+ suporta
   `networkingMode=mirrored` no `.wslconfig`. Nele, o WSL compartilha as
   interfaces do host: `127.0.0.1` funciona nos **dois** sentidos, o firewall do
   Windows aplica-se uniformemente e o problema do IP some. Esta máquina está em
   `nat` e o build (26100) suporta espelhado — a instalação deve migrar.
   ```ini
   [wsl2]
   networkingMode=mirrored
   ```
   Contrapartida: modo espelhado tem incompatibilidades conhecidas com algumas
   VPNs corporativas e com bridges customizadas do Docker. Por isso o suporte a
   NAT não pode ser removido.

2. **Nunca codificar endereço.** O núcleo publica onde está. Ver R3/ADR-0006
   abaixo.

**O arquivo de endpoint.** Ao subir, o núcleo escreve:

```
/mnt/c/Users/<usuário>/.zordon/endpoint.json      (visto do WSL)
%USERPROFILE%\.zordon\endpoint.json                (visto do Windows)
```

```json
{
  "version": 1,
  "startId": "01J9X2K7QF8ZP3",
  "startedAt": "2026-09-17T22:31:04Z",
  "networkingMode": "mirrored",
  "endpoints": ["ws://127.0.0.1:8777/zwp/v1", "ws://<WSL_IP>:8777/zwp/v1"],
  "token": "<256 bits, base64url>",
  "protocol": { "min": 1, "max": 1 },
  "pid": 4211
}
```

Os clientes Windows leem o arquivo, tentam os endpoints em ordem e usam o
`token`. O arquivo resolve, de uma vez, **descoberta**, **autenticação** e
**prova de co-localização** (só quem roda como o mesmo usuário na mesma máquina
consegue ler). Ver [ADR-0006](../adr/ADR-0006-descoberta-por-arquivo-de-endpoint.md).

O arquivo é reescrito de forma atômica (escreve `.tmp`, depois `rename`) a cada
início do núcleo, e os clientes observam o diretório para reconectar sozinhos.

### R4 — Firewall do Windows bloqueia WSL → Windows

Em modo NAT, o tráfego do WSL chega ao Windows como vindo de uma rede externa.
O Windows Defender Firewall, por padrão, **bloqueia conexões de entrada** nessa
direção. O sintoma é cruel: o Windows fala com o WSL sem problema, o WSL não
fala com o Windows, e não há mensagem de erro — só timeout.

**Mitigação arquitetural: eliminar a necessidade.** No Zordon, **só existe um
listener, e ele fica no WSL**. `zordon-desktop` e `zordon-host` são sempre
clientes; eles abrem a conexão WebSocket para o núcleo e a mantêm aberta. Toda
comunicação do núcleo para o Windows (inclusive chamadas do `WindowsBridge`)
viaja como requisição ZWP **no sentido inverso da mesma conexão** — o protocolo
é bidirecional justamente para isso.

Consequência de projeto: o `WindowsBridge` no núcleo não "chama o Windows"; ele
publica uma requisição na sessão ZWP do host conectado e aguarda a resposta. Se
nenhum host está conectado, a operação falha imediatamente com
`ERR_BRIDGE_UNAVAILABLE` em vez de travar.

Isso também cobre o caso do usuário com VPN corporativa, onde nenhuma regra de
firewall que a gente crie sobreviveria.

### R12 — Qualquer coisa no PC pode falar com a porta do núcleo

Um WebSocket em `127.0.0.1:8777` é alcançável por **qualquer** processo do
usuário — e, pior, por **qualquer página web aberta no navegador**, porque a
política de mesma origem não se aplica a WebSockets. Uma aba maliciosa pode
tentar `new WebSocket("ws://127.0.0.1:8777/zwp/v1")` e, se conectar, teria um
assistente com acesso a arquivos e shell.

**Mitigação em camadas (todas obrigatórias):**

1. **Token por inicialização** no cabeçalho `Authorization: Bearer`. Navegadores
   não conseguem definir cabeçalhos customizados em `WebSocket`, o que por si só
   já barra o ataque de aba. O token vem do arquivo de endpoint, ilegível para
   o navegador.
2. **Validação de `Origin`.** Rejeitar qualquer handshake que traga um cabeçalho
   `Origin` (clientes nativos não enviam; navegadores sempre enviam).
3. **Bind restrito.** Em modo espelhado, bind em `127.0.0.1`. Em NAT, bind em
   `0.0.0.0` é inevitável para o relay funcionar — nesse caso o token é a única
   defesa, e o núcleo deve recusar conexões cujo endereço de origem não seja da
   sub-rede do WSL nem do gateway.
4. **Nada de execução arbitrária no protocolo.** Não existe método ZWP
   `system.exec`. Mesmo um cliente autenticado só alcança ações que passam pelo
   `PermissionEngine`.
5. **Permissão vinculada à sessão.** Uma confirmação RED vale para uma ação, uma
   vez, naquela sessão — não vira permissão persistente.

---

## 4. Riscos de interop

### R5 — `WSL_INTEROP` não existe dentro de um serviço systemd

**[medido]** Nesta sessão interativa, `WSL_INTEROP=/run/WSL/<pid>_interop` — o
número é o PID da sessão. O interop do WSL (poder rodar `powershell.exe` de
dentro do Linux) depende dessa variável apontar para um socket **vivo de uma
sessão de login**.

Serviços iniciados pelo systemd **não herdam** esse ambiente. O resultado é que
`powershell.exe` chamado de dentro do `zordon.service` falha, frequentemente com
um erro obscuro ou simplesmente sem saída — mesmo que o mesmo comando funcione
perfeitamente no terminal do usuário. **[medido]** no terminal:
`powershell.exe -NoProfile -Command '$PSVersionTable.PSVersion'` → `5.1.26100.xxxx`.

Isso derruba a abordagem "ingênua" de Windows Bridge, que seria o núcleo chamar
`powershell.exe` direto.

**Mitigação.** O `WindowsBridge` **não usa interop**. Ele usa a conexão ZWP com
o `zordon-host`, que já roda no Windows, já está na sessão do usuário, e não
depende de socket nenhum do WSL. Ver R4 acima e
[ADR-0005](../adr/ADR-0005-host-windows-dedicado.md).

Interop fica disponível apenas como **fallback degradado** para uma lista curta
de operações, quando nenhum host estiver conectado. Nesse caso o núcleo precisa
descobrir um `WSL_INTEROP` válido varrendo `/run/WSL/*_interop` e testando — um
hack que deve ser isolado em uma classe só, marcado como tal, e nunca ser o
caminho principal.

### R6 — Não há áudio confiável dentro do WSL

O WSL2 não expõe dispositivos de áudio. O WSLg fornece um servidor PulseAudio,
mas com latência variável, sem controle de dispositivo, sem cancelamento de eco e
quebrando quando não há sessão gráfica. Para um assistente de voz sempre ativo,
isso é inviável.

**Mitigação.** Captura e reprodução acontecem no Windows, no `zordon-host`, com
`javax.sound.sampled`. O áudio trafega como frames binários ZWP para o núcleo,
que repassa ao sidecar de voz. Detalhes e orçamento de latência em
[Voz](../specs/voice/design.md) e [ADR-0009](../adr/ADR-0009-captura-windows-inferencia-wsl.md).

Custo de banda: PCM 16 kHz mono 16-bit = **32 KB/s**. Em loopback local isso é
irrelevante, e é a razão pela qual centralizar a inferência no WSL sai barato.

### R7 — O relógio do WSL desvia após suspensão

Quando o Windows suspende ou hiberna, a VM do WSL congela. Ao voltar, o relógio
do convidado pode ficar minutos ou horas atrasado até a sincronização. Isso
afeta:

- **Scheduler:** jobs `cron` disparam em massa ou não disparam.
- **TLS:** certificados podem parecer não-válidos-ainda, quebrando chamadas ao
  provider de IA.
- **Auditoria e memória:** carimbos de tempo fora de ordem corrompem a narrativa
  ("o projeto que trabalhamos ontem" depende disso).

**[medido]** Em 2026-09-18, com o Windows acordado, o relógio do WSL deu passos
a cada ~30 s (`systemd-resolved: Clock change detected`; `journald: Time jumped
backwards`). O teste do microfone media o prazo no relógio de parede e, quando um
passo para trás caiu dentro da margem, o microfone ficou ligado até o usuário
desligá-lo ([SPEC-009](../specs/voice/SPEC-009-frames-de-audio-e-teste-do-microfone.md)).
Regra: **todo prazo é medido em relógio monotônico**; o relógio de parede serve
só para exibir horários.

**Mitigação.**
- O `Scheduler` usa relógio monotônico para intervalos e relógio de parede
  apenas para expressões cron; ao detectar salto de relógio maior que 5 s,
  recalcula todos os próximos disparos e emite `SYSTEM_CLOCK_JUMP`.
- Deduplicação por `(automation_id, scheduled_for)` impede disparo duplicado.
- O `zordon-host` detecta retorno de suspensão do Windows
  (`SystemEvents.PowerModeChanged`) e envia `system.resumed` ao núcleo, que
  reavalia watchers e força ressincronização.

### R8 — Caminhos são dois mundos

`C:\Users\<user>\projeto` e `/mnt/c/Users/<user>/projeto` são o mesmo lugar por
dois nomes — e note que o nome ali é o usuário **do Windows**, não o do WSL ([R21](#r21--o-usuário-do-windows-não-é-o-usuário-do-wsl)). Um agente que confunde os dois falha de formas confusas. Agrava-se
com: espaços em nomes, acentos (**[medido]** o Windows cria diretórios
localizados em `C:\Users`, como `Todos os Usuários` em pt-BR), caminhos UNC
(`\\wsl.localhost\Ubuntu\...`),
maiúsculas/minúsculas (NTFS insensível, ext4 sensível) e o limite de 260
caracteres do Windows.

**Mitegação.** Um tipo só, em `zordon-api`:

```java
/** Caminho canônico, sempre resolvido, com origem explícita. */
public record ZPath(Origin origin, String canonical) {
    public enum Origin { WINDOWS, WSL }
    public String toWindows() { ... }   // C:\Users\x\p
    public String toWsl()     { ... }   // /mnt/c/Users/x/p
}
```

Regras: nenhuma API de Skill aceita `String` como caminho — só `ZPath`.
A conversão é feita por tabela de montagem lida de `/proc/mounts` na
inicialização (**[medido]**: `C:\ on /mnt/c type 9p`), nunca por concatenação de
string. `wslpath` não é chamado no caminho quente (é um processo por chamada).

### R21 — O usuário do Windows não é o usuário do WSL

**[medido]** Nesta máquina os dois nomes são **diferentes**: `$env:USERPROFILE`
aponta para um diretório em `C:\Users\` cujo nome não existe em `/home/` no WSL.

Isso quebra silenciosamente qualquer código que derive o perfil do Windows a
partir de `$USER` ou `$HOME` — e é um erro fácil de cometer porque, na maioria
das instalações, os dois nomes coincidem e tudo funciona na máquina de quem
escreveu.

Afeta diretamente:

- o caminho do `endpoint.json` ([ADR-0006](../adr/ADR-0006-descoberta-por-arquivo-de-endpoint.md));
- `ReadWritePaths` no `zordon.service`, que **não** pode usar `%i`;
- as listas `readable` e `forbidden` da política de caminhos;
- qualquer conversão `ZPath` que envolva o diretório de perfil.

**Mitigação.** O perfil do Windows é resolvido **perguntando ao Windows**, nunca
derivando:

```bash
WIN_HOME=$(wslpath "$(powershell.exe -NoProfile -Command '$env:USERPROFILE' | tr -d '\r')")
```

Isso roda no **instalador** e no `zordon-host` (que já está no Windows e tem o
valor de graça), não no núcleo em tempo de execução — o núcleo sob systemd não
tem interop confiável ([R5](#r5--wsl_interop-não-existe-dentro-de-um-serviço-systemd)).
O valor resolvido é gravado na configuração; o núcleo apenas o lê.

### R9 — I/O em `/mnt/c` é ordens de grandeza mais lento

**[medido]** nesta máquina, mesmo hardware, mesma sessão:

| Operação | ext4 (`~/`) | 9p (`/mnt/c`) | Fator |
|---|---|---|---|
| Criar 200 arquivos pequenos | 13 ms | 611 ms | **47×** |
| Escrever 64 MB sequenciais | 100 ms | 331 ms | 3,3× |

A penalidade está em **operações de metadado**, não em vazão. Isso é
característico do 9p/drvfs e não tem solução, só mitigação.

**Consequências de projeto (obrigatórias):**

- `~/.zordon/zordon.db` fica no ext4. **Nunca** em `/mnt/c`. Um SQLite em 9p com
  WAL é uma fonte inesgotável de corrupção e lentidão.
- Índices, embeddings, caches e logs: ext4.
- O arquivo de endpoint é a **única** escrita rotineira em `/mnt/c`, e acontece
  uma vez por inicialização.
- Skills que varrem diretórios do Windows (`files.directorySearch`) devem ter
  timeout generoso, limite de resultados e aviso explícito de custo. Uma busca
  recursiva em `C:\` de dentro do WSL pode levar minutos.
- Builds e ferramentas de desenvolvimento em projetos que moram no Windows
  devem, quando possível, ser executados **pelo host** (`zordon-host`) em vez de
  pelo núcleo via `/mnt/c`.

### R10 — `vmmem` cresce e não devolve

O WSL2 aloca memória sob demanda, mas historicamente devolve mal ao Windows.
Com STT local, embeddings e possivelmente um LLM local, o `zordon-core` pode
fazer o `vmmem` chegar a vários GB e ficar lá.

**Mitigação.** `.wslconfig` com teto explícito e recuperação automática:

```ini
[wsl2]
memory=8GB
processors=4
autoMemoryReclaim=gradual
```

Do lado do núcleo: `-XX:MaxRAMPercentage` em vez de `-Xmx` fixo, e
`-XX:+UseZGC` ou `ZGC` geracional para pausas curtas (é um processo interativo).
O sidecar de voz descarrega o modelo de STT da memória após N minutos sem uso e
recarrega sob demanda — o custo de recarga (~1-2 s) só é pago na primeira frase
depois de um longo silêncio.

### R14 — A GPU é disputada

O usuário desenvolve com Unreal Engine. CUDA no WSL2 funciona (via
`/usr/lib/wsl/lib`, sem driver dentro da distro), mas a mesma GPU é do jogo, do
editor e do STT.

**Mitigação.** Política de GPU configurável com três modos:
`always` (usa GPU sempre), `never` (CPU sempre), `adaptive` (padrão: usa GPU,
mas cai para CPU quando o `SystemAgent` detecta uso de VRAM acima de um limite
ou um processo em lista de prioridade — `UnrealEditor.exe`, jogos — em primeiro
plano). Whisper `small` em CPU nesta classe de máquina roda em tempo quase real;
a degradação é aceitável e silenciosa.

---

## 5. Tabela de referência rápida

O que funciona, o que não funciona, verificado:

| Quero... | Solução | Observação |
|---|---|---|
| Saber o perfil do Windows | `powershell.exe -Command '$env:USERPROFILE'` + `wslpath` | **[medido]**: difere do usuário WSL nesta máquina (R21) |
| Windows falar com serviço no WSL | `localhost:porta`, bind `0.0.0.0` | Em NAT, precisa de `localhostForwarding=true` (padrão) |
| WSL falar com serviço no Windows | **Não faça.** Inverta a conexão | Firewall bloqueia (R4) |
| Descobrir o IP do WSL | `ip -4 addr show eth0` | Muda a cada boot; não persista |
| Rodar comando Windows do WSL | `powershell.exe` via interop | **Falha sob systemd** (R5) |
| Manter o WSL vivo | systemd + tarefa no logon + supervisor | Três camadas, todas necessárias (R1) |
| Guardar banco de dados | `~/.zordon/` (ext4) | Nunca `/mnt/c` (R9) |
| Trocar arquivo entre os dois lados | `/mnt/c/Users/<u>/.zordon/` | Escrita atômica; raro (R9) |
| Áudio | Captura e reprodução no Windows | WSLg não serve (R6) |
| Notificação nativa do Windows | `zordon-host` | Via ZWP inverso |
| Saber a versão do WSL/modo de rede | `wslinfo --networking-mode` | **[medido]**: `nat` |

## 6. Riscos da camada de defesa

Os riscos R22 a R25 não vêm do WSL — vêm de o Zordon ter passado a ser também um
produto de segurança. Eles são tratados em [Defesa](../security/defense.md) e
[Comunicação](../security/communication.md), mas pertencem a este registro porque são
capazes de anular o valor do subsistema inteiro:

- **R22 — Fadiga de alerta.** O modo de falha mais provável da defesa não é não
  detectar: é detectar demais, o usuário desligar as notificações, e a proteção
  virar zero com aparência de cem. Mitigação: correlação em incidente,
  deduplicação, resumo diário, linha de base de 7 dias, orçamento de 3
  interrupções CRITICAL por hora, e a regra "sem ação possível ⇒ é registro, não
  notificação". Termômetro: `zordon.notify.dismissed_without_reading`.

- **R23 — Falso positivo contém algo legítimo.** Um build longo parecido com
  modificação em massa, uma ferramenta de backup parecida com beaconing.
  Mitigação: toda contenção autônoma é **reversível e com prazo**; o custo de um
  falso positivo é uma autorização, não um estrago. Métrica
  `zordon.detect.false_positive` por detector; detector com taxa alta é
  desligado até ser corrigido.

- **R24 — Detecção cara demais.** Uma defesa que deixa a máquina lenta é uma
  defesa que o usuário desliga. Mitigação: detectores reusam a telemetria que o
  `zordon-monitor` já coleta ([Automação §5](../specs/automation/design.md#5-coleta-sem-polling)),
  detector `EXPENSIVE` não roda no caminho quente, e o requisito de <3% de CPU em
  repouso continua valendo com a defesa ligada.

- **R25 — Falsa sensação de proteção.** É o risco mais grave desta categoria,
  porque o dano acontece quando o usuário *não* é atacado pelo que o Zordon vê, e
  sim pelo que ele não vê. Mitigação: nenhum texto do produto afirma substituir
  antivírus; a tela de Segurança mostra o estado do Defender ao lado do Defense
  Engine; se o Defender estiver desativado, o Zordon avisa e não finge cobrir a
  lacuna ([ADR-0017](../adr/ADR-0017-zordon-nao-e-antivirus.md)).

## 7. Riscos que não são do WSL, mas matam o projeto

- **R11 — Prompt injection.** Um `README.md`, uma issue do GitHub ou um log de
  container podem conter instruções endereçadas ao modelo. Se o resultado de
  ferramenta entra no contexto como texto puro, o conteúdo do mundo vira
  instrução. É o risco mais grave do sistema inteiro porque combina
  probabilidade alta com impacto crítico. Tratamento em
  [Segurança §6](../security/model.md#6-prompt-injection).

- **R13 — Custo sem teto.** Um agente em laço que chama ferramentas e reprocessa
  contexto grande pode queimar dinheiro rápido. Todo agente tem orçamento de
  passos, tokens e tempo de parede; o núcleo tem teto diário configurável que,
  ao ser atingido, degrada para modelo local ou recusa.
  Ver [Core §7](../specs/core/design.md#7-orçamento-e-custo).

- **R15 — Segredos.** Chave de API do provider, tokens de MCP servers,
  credenciais que o usuário menciona em conversa. Nunca em `config.toml` em
  texto plano, nunca em log, nunca ecoado em evento.
  Ver [Segurança §5](../security/model.md#5-segredos).

- **R18 — MCP instável.** Um servidor MCP que trava no `initialize` não pode
  travar a inicialização do núcleo, e um que trava numa chamada não pode travar o
  turno. Conexão assíncrona, timeout por chamada, disjuntor após N falhas.

- **R19 — Migração de schema.** Memória e auditoria são dados que o usuário não
  pode perder e não pode recriar. Versionamento de schema desde o primeiro
  commit, migrações unidirecionais testadas, backup automático antes de migrar.

## 8. Riscos aceitos conscientemente

| Risco | Por que aceitamos |
|---|---|
| Voz para de funcionar se o usuário matar `zordon-host` | O host é autostart e leve; recuperar exige apenas relogar |
| Sem redundância de núcleo | É um assistente pessoal, não um serviço crítico |
| Dependência de um provider externo de IA no caminho principal | Mitigado pela abstração `AiProvider` e fallback local, não por multi-provider ativo |
| Modo espelhado pode quebrar com VPN corporativa | Suporte a NAT é mantido como alternativa |
| Primeira resposta após longo silêncio é mais lenta | Consequência do descarregamento de modelo (R10); trocamos latência por RAM |
| A defesa não cobre malware desconhecido sem comportamento observável | Limite declarado, não disfarçado ([ADR-0017](../adr/ADR-0017-zordon-nao-e-antivirus.md)) |
| O usuário não consegue usar o Zordon para liberar espaço em disco | Consequência de não existir exclusão ([ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md)) |
| O cofre de quarentena consome espaço | Retenção de 90 dias, com pergunta antes de expirar |
| Um fork proprietário do Zordon é permitido pela licença | Defesa correta é proveniência, não licença ([ADR-0013](../adr/ADR-0013-apache-2.md)) |
