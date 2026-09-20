---
document: spec-007
module: host
section: spec
version: 1
updatedAt: 2026-09-18
securityLevel: public
tags: [spec,host,windows,microfone,autostart,wsl,privacidade]
specId: SPEC-007
---

# SPEC-007 — Host do Windows

| Campo | Valor |
|---|---|
| **Status** | DONE |
| **Owner** | Willyan Faria |
| **Agente responsável** | `HostAgent` |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M2 |
| **Supera** | — |

## 1. Objetivo

Um processo Windows sem janela, iniciado no logon, que controla o microfone de
verdade a pedido do núcleo e diz a verdade sobre o que fez.

## 2. Problema

A [SPEC-006](../voice/SPEC-006-tela-e-estado-da-voz.md) definiu o que o núcleo
pede ao host (`audio.setCaptureEnabled`, `audio.listDevices`,
`audio.selectDevice`), mas ninguém responde: o estado da voz mostra "host do
Windows não conectado" para sempre. O WSL não tem áudio confiável
([R6](../../architecture/windows-wsl.md#r6--não-há-áudio-confiável-dentro-do-wsl)),
então a captura precisa acontecer no Windows
([ADR-0009](../../adr/ADR-0009-captura-windows-inferencia-wsl.md)).

## 3. Escopo

- Módulo `zordon-host`: Java 25, sem JavaFX, sem console. Depende só de
  `zordon-api` e `zordon-zwp` ([Componentes §4](../../architecture/components.md#4-regras-de-dependência-verificadas-no-build)).
- Conexão com o núcleo como `kind: host`, capacidade `audio.capture`, pelo
  `%USERPROFILE%\.zordon\endpoint.json`, com a reconexão do `CoreConnection`.
- Microfone com `javax.sound.sampled`: listar dispositivos que capturam
  16 kHz, mono, PCM 16 bits little-endian; escolher um; ligar e desligar.
- Os três métodos de áudio da SPEC-006, respondendo o que de fato aconteceu.
- Com a captura ligada, ler frames de 20 ms (640 bytes) sem parar e entregar a
  um `FrameSink`. Nesta SPEC o sink só conta e descarta.
- Núcleo: quando o host responde `enabled` diferente do pedido com um `reason`,
  o estado da voz mostra esse motivo.
- Autostart: tarefa agendada `Zordon Host` no logon, com `javaw.exe` (sem
  janela) e reinício em caso de falha.
- Supervisor do WSL pelo agendador ([ADR-0027](../../adr/ADR-0027-supervisor-do-wsl-pelo-agendador.md)):
  `Zordon WSL Boot` repete a cada 5 min.
- Instalação: `packaging/windows/install-host.sh`, rodado no WSL, compila o host,
  copia para `%LOCALAPPDATA%\Programs\Zordon\host` e mostra o comando que registra
  as tarefas.

## 4. Não escopo

- Enviar o áudio ao núcleo: frames binários e controle de fluxo têm item próprio
  no [roadmap](../../roadmap.md#m2--voz). Até lá, o áudio lido é descartado na
  memória.
- Reprodução (`audio.play`, `audio.stop`) e capacidade `audio.playback`: dependem
  dos frames binários.
- `bridge.*` (abrir aplicativo, clipboard, captura de tela): M3, com o executor
  mediado.
- Notificação nativa do Windows e eventos de energia (R7, R20): SPEC própria.
- Instalador MSI com JRE embutido (`jpackage`): exige build no Windows; fica para
  quando houver CI Windows.
- Teste do microfone e calibração na tela de Voz: entram com os frames, porque
  precisam do nível do sinal no núcleo.

## 5. Arquitetura

```text
 Windows                                            WSL
 ───────                                            ───
 Agendador ── logon ──► javaw zordon.host.ZordonHost
                         │
                         ├── CoreConnection (kind host, audio.capture) ──► núcleo
                         │      handle audio.setCaptureEnabled
                         │      handle audio.listDevices
                         │      handle audio.selectDevice
                         │      onOffline ──► microfone desliga
                         │
                         └── Microphone ── TargetDataLine (javax.sound)
                                 │ 20 ms
                                 ▼
                             FrameSink (conta e descarta)

 Agendador ── logon + a cada 5 min ──► wsl.exe -d Ubuntu --exec /bin/true
```

| Classe | Responsabilidade |
|---|---|
| `ZordonHost` | `main`: logs, conexão, tratadores, desligamento |
| `HostPaths` | `endpoint.json` e diretório de logs no Windows |
| `AudioMethods` | Os três tratadores ZWP; traduz para `Microphone` e de volta |
| `Microphone` | Estado da captura, dispositivo escolhido, thread de leitura |
| `SoundSystem` | Fronteira com `javax.sound.sampled`; falsa nos testes |
| `FrameSink` / `DiscardingSink` | Destino dos frames |

Invariantes do microfone:

1. O host inicia com a captura **desligada**, em toda conexão.
2. Captura só liga por pedido do núcleo na conexão atual. Perder a conexão
   desliga a captura antes de qualquer tentativa de reconectar.
3. A resposta a `audio.setCaptureEnabled` é o estado da linha **depois** da
   operação: `enabled: true` só com a linha aberta e iniciada; `enabled: false`
   só com ela parada e fechada.
4. Nenhum byte de áudio vai para disco ou log.

## 6. Fluxo

1. Logon → agendador inicia `javaw` → host lê o `endpoint.json` e conecta.
2. Hello com `kind: host`, `capabilities: ["audio.capture"]`.
3. O núcleo pede `audio.setCaptureEnabled {enabled: false}` (motor ausente) → o
   host confirma `{enabled: false}`.
4. O núcleo reaplica o dispositivo preferido com `audio.selectDevice`, se houver.
5. Pedido de ligar → abre a linha no dispositivo escolhido, inicia a leitura →
   `{enabled: true}`. Se a linha não abrir (ocupada, bloqueada pela privacidade
   do Windows) → `{enabled: false, reason}`.
6. Conexão cai → captura desliga → `CoreConnection` reconecta → volta ao passo 2.
7. Encerramento do processo → captura desliga.

## 7. Interfaces

Respostas do host (completam a SPEC-006 §7):

| Método | Retorno |
|---|---|
| `audio.setCaptureEnabled {enabled}` | `{enabled, reason?}` — `reason` quando não fez o que foi pedido |
| `audio.listDevices {}` | `{devices[{id, name, default}], selected}` |
| `audio.selectDevice {deviceId}` | `{selected, name}`; id desconhecido → `ERR_NOT_FOUND` |

Dispositivos: `id: "default"` é o padrão do Windows (`AudioSystem.getTargetDataLine`
com o formato); os demais usam o nome do mixer como id, que é o que o Windows
mantém estável entre reinícios. Só entram na lista mixers que abrem uma
`TargetDataLine` no formato de captura.

Escolher outro dispositivo com a captura ligada reabre a linha no novo; se falhar,
a captura fica desligada, e a próxima resposta de `audio.setCaptureEnabled`
diz isso.

Java:

- `SoundSystem`: `List<Device> devices()`, `CaptureLine open(String deviceId)`.
- `CaptureLine`: `int read(byte[] buffer)`, `void close()`.
- `Microphone`: `boolean enable()`, `void disable()`, `boolean capturing()`,
  `void select(String deviceId)`, `Optional<String> lastFailure()`.
- `FrameSink`: `void accept(byte[] frame, int length)`.

## 8. Eventos

Nenhum evento novo. O efeito aparece no `VOICE_STATE` da SPEC-006: host
conectado, captura confirmada, dispositivo.

## 9. Dados

- Nenhum estado persistido no host. O dispositivo preferido é do núcleo
  (`voice.json`, SPEC-006 §9), que o reaplica a cada conexão.
- Logs em `%LOCALAPPDATA%\Zordon\logs\host.log`, com rotação diária e sem
  exclusão dos antigos: o Zordon não apaga arquivo
  ([ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md)), e o host
  escreve poucas linhas por dia. Nomes de dispositivo só em DEBUG.
- Instalação em `%LOCALAPPDATA%\Programs\Zordon\host`. O host nunca escreve
  ali ([Componentes §4](../../architecture/components.md#4-regras-de-dependência-verificadas-no-build), regra 10).

## 10. Segurança

- O host não executa processo, não apaga arquivo e não chama código nativo neste
  marco. ArchUnit no próprio módulo verifica.
- O token vem do `endpoint.json` no perfil do usuário e não aparece em log.
- A captura não sobrevive à conexão (§5, invariante 2): um host que perdeu o
  núcleo não tem mais quem o mande desligar.
- `javaw.exe` roda com o usuário logado, sem elevação.

## 11. Permissões

Ligar o microfone é decisão do usuário pela tela de Voz, mediada pelo núcleo
(SPEC-006). O host não decide nada: executa e relata. A privacidade do Windows
("Permitir que aplicativos da área de trabalho acessem o microfone") continua
valendo; se ela bloquear, o host relata o motivo.

## 12. Observabilidade

- INFO: conexão, desconexão, captura ligada/desligada, dispositivo trocado.
- WARN: linha que não abriu, com o motivo.
- DEBUG: frames lidos por minuto, nomes de dispositivos.
- A tela de Voz mostra o host conectado e a captura confirmada; a tela de
  Diagnóstico conta o host entre os clientes.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Sem `endpoint.json` | Tenta de novo com o backoff do `CoreConnection`; nada captura |
| Microfone ocupado ou bloqueado | `{enabled: false, reason}`; WARN |
| Nenhum dispositivo no formato | `devices` só com `default`; ligar falha com motivo |
| Dispositivo removido durante a captura | Leitura falha → captura desliga; próxima resposta diz `enabled: false` |
| Núcleo cai | Captura desliga; reconexão com backoff |
| Duas instâncias do host | O núcleo usa a mais recente e manda a anterior largar o microfone (SPEC-006 §13) |
| Java 25 ausente no Windows | `install-host.sh` para e mostra como instalar (`winget install EclipseAdoptium.Temurin.25.JRE`) |

## 14. Testes

- Unidade no `zordon-host` com `SoundSystem` falso: invariantes do microfone,
  tratadores, troca de dispositivo, falhas.
- ArchUnit no `zordon-host`.
- Núcleo: `reason` do host no estado da voz.
- Contrato dos scripts: o teste lê `register-tasks.ps1` e confere tarefas,
  gatilhos e ação.
- Verificação real, registrada no roadmap: host no Java do Windows contra o
  núcleo do serviço, dispositivos reais listados, captura confirmada desligada.

### Evidências (2026-09-18)

- `install-host.sh` instalou em `%LOCALAPPDATA%\Programs\Zordon\host` e iniciou o
  host no Java 25 do Windows, sem janela. Log em
  `%LOCALAPPDATA%\Zordon\logs\host.log`.
- O host conectou pelo IP do WSL (segundo endereço do `endpoint.json`); o núcleo
  pediu a captura desligada e o host confirmou em 56 ms. `voice.status`: host
  conectado, captura `off` com `confirmedAt`.
- `voice.devices` pelo núcleo listou os microfones reais: "Padrão do Windows" e
  "Microfone (Logi USB Headset)".
- A captura não foi ligada de verdade: o núcleo só pede com motor de voz, e ligar
  por fora acenderia o indicador de microfone sem motivo. O caminho de ligar está
  coberto por `MicrophoneTest`.
- `register-tasks.ps1` passou no parser do PowerShell; o registro das tarefas é
  passo do usuário, como no M0.

## 15. Critérios de aceite

- `CA-1` Dado o host iniciado, então ele se apresenta como `kind: host` com a
  capacidade `audio.capture`, e só ela. (A [SPEC-011](../voice/SPEC-011-motor-de-voz-ouvir-e-falar.md)
  acrescentou `audio.playback`, para tocar a fala.)
- `CA-2` Dado um pedido de ligar, então o host abre a linha em 16 kHz, mono,
  16 bits little-endian e responde `enabled: true` só depois de iniciá-la; dado
  um pedido de desligar, responde `enabled: false` só depois de fechá-la.
- `CA-3` Dada uma linha que não abre, então o host responde `enabled: false` com
  `reason`, e o estado da voz no núcleo mostra esse motivo.
- `CA-4` Dada a captura ligada, quando a conexão com o núcleo cai, então a
  captura desliga; na reconexão ela continua desligada até um novo pedido.
- `CA-5` Dado o host, então `audio.listDevices` lista `default` e só os
  dispositivos que capturam no formato; `audio.selectDevice` com id desconhecido
  falha com `ERR_NOT_FOUND`; trocar de dispositivo com a captura ligada reabre a
  linha no novo.
- `CA-6` Dada a captura ligada, então frames de 640 bytes são lidos sem parar e
  entregues ao sink; nenhum byte de áudio é escrito em disco ou log.
- `CA-7` Dado o código do host, então ele não depende do núcleo nem de módulos de
  capacidade, não executa processo, não apaga arquivo e não chama código nativo.
- `CA-8` Dado `register-tasks.ps1`, então ele registra `Zordon Host` no logon com
  `javaw.exe` e reinício em falha, e `Zordon WSL Boot` no logon e a cada 5 min,
  sem instâncias paralelas.

## 16. Impacto em outros módulos

- Novo módulo `zordon-host`; `settings.gradle.kts`.
- `zordon-core`: `VoiceService` usa o `reason` do host.
- `packaging/windows`: `register-tasks.ps1` (tarefa do host e repetição),
  `install-host.sh`.
- Documentação: [R1](../../architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows)
  (segunda camada pelo agendador), [ZWP §5](../../api/zwp-protocol.md#5-métodos--núcleo--cliente)
  (`reason`), [Instalação](../../operations/install.md) e
  [Primeiros passos](../../operations/quickstart.md) com o host.

## 17. Dependências

- [SPEC-006](../voice/SPEC-006-tela-e-estado-da-voz.md) — contrato de voz
- [ADR-0005](../../adr/ADR-0005-host-windows-dedicado.md) · [ADR-0009](../../adr/ADR-0009-captura-windows-inferencia-wsl.md) · [ADR-0027](../../adr/ADR-0027-supervisor-do-wsl-pelo-agendador.md)
- [Windows/WSL](../../architecture/windows-wsl.md) — R1, R6
