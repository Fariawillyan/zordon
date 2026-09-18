---
document: ops-install
module: operations
section: install
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [systemd,wslconfig,runbook,troubleshooting]
specId: null
---

# Operação — instalação e runbook

Runbook de instalação, configuração e diagnóstico. Os valores aqui refletem o
ambiente verificado: Windows 11 build 26100, WSL2 Ubuntu com systemd, Java 25
(Temurin), rede em modo `nat`.

> **Atenção — o usuário do Windows e o do WSL podem ter nomes diferentes.**
> Nesta máquina, **[medido]**, eles são **diferentes**.
> (`$env:USERPROFILE` aponta para um nome que não existe em `/home/`.) Nenhum caminho pode assumir que os
> dois são iguais. O instalador resolve o perfil do Windows perguntando ao
> próprio Windows, nunca derivando de `$USER`:
>
> ```bash
> WIN_HOME=$(wslpath "$(powershell.exe -NoProfile -Command '$env:USERPROFILE' | tr -d '\r')")
> ```
>
> No `zordon.service`, isso significa que `ReadWritePaths` **não** pode usar
> `%i`; o caminho é resolvido na instalação e gravado no unit.

## 1. Pré-requisitos

| Item | Verificação |
|---|---|
| Windows 11 22H2+ | `winver` |
| WSL 2.0+ | `wsl --version` |
| Distro Ubuntu com systemd | `ps -p 1 -o comm=` → `systemd` ✓ |
| JDK 25 no WSL | `java -version` → `25.0.2` ✓ |
| JDK 25 no Windows | Embutido no MSI via `jpackage` |
| GPU NVIDIA (opcional) | `nvidia-smi` dentro do WSL |

## 2. Configuração do WSL

### `%USERPROFILE%\.wslconfig`

```ini
[wsl2]
memory=8GB
processors=4
autoMemoryReclaim=gradual
vmIdleTimeout=-1

# Recomendado: elimina o problema de IP variável e simplifica o firewall.
# Incompatível com algumas VPNs corporativas e bridges customizadas do Docker —
# se a rede quebrar, remova estas duas linhas e o Zordon cai para o modo NAT.
networkingMode=mirrored
hostAddressLoopback=true
```

`vmIdleTimeout=-1` impede que o WSL desligue a VM por ociosidade. Sem isso, o
núcleo pode ser derrubado depois de um período sem interação —
[R2](../architecture/windows-wsl.md#r2--o-wsl-é-derrubado-por-fora).

Após editar: `wsl --shutdown` e reiniciar a distro.

### `/etc/wsl.conf` (dentro da distro)

```ini
[boot]
systemd=true

[user]
default=<user>

[interop]
enabled=true
appendWindowsPath=false
```

`appendWindowsPath=false` tira o `PATH` do Windows do ambiente Linux. Isso é
higiene importante: sem ele, um `ProcessRunner` pode resolver acidentalmente um
executável do Windows quando esperava o do Linux. O Zordon não depende do
interop no caminho principal ([R5](../architecture/windows-wsl.md#r5--wsl_interop-não-existe-dentro-de-um-serviço-systemd)).

## 3. Unit systemd

Decisão: **unit de sistema**, não de usuário.

Uma unit de usuário (`systemctl --user`) exige `loginctl enable-linger` para
rodar sem sessão aberta, e o comportamento de linger no WSL é inconsistente
entre versões. Unit de sistema com `User=` sobe de forma previsível assim que o
systemd da distro inicia, que é exatamente o gatilho que a tarefa agendada do
Windows aciona.

```ini
# /etc/systemd/system/zordon.service
[Unit]
Description=Zordon Core
After=network.target
StartLimitIntervalSec=300
StartLimitBurst=10

[Service]
Type=notify
NotifyAccess=main
User=<user>
Group=<user>
WorkingDirectory=/home/<user>/.local/share/zordon
ExecStart=/home/<user>/.local/share/zordon/bin/zordon-core
Restart=always
RestartSec=3
TimeoutStopSec=20

Environment=JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50 -XX:+UseZGC
Environment=ZORDON_HOME=/home/<user>/.zordon

# Endurecimento — o núcleo não precisa de nada disso
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=read-only
# Resolvido na instalação. NUNCA use %i aqui: o usuário Windows pode ter
# outro nome — foi o caso nesta máquina.
ReadWritePaths=/home/<user>/.zordon /mnt/c/Users/<user>/.zordon
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true

[Install]
WantedBy=multi-user.target
```

`Type=notify` faz o systemd considerar o serviço ativo só depois que o núcleo
sinaliza que o `ZwpServer` está escutando e o `endpoint.json` foi escrito. Com
`Type=simple`, o serviço apareceria "ativo" antes de estar utilizável.

O endurecimento (`ProtectSystem=strict`, `ProtectHome=read-only`) é uma segunda
linha de defesa atrás do `PermissionEngine`: mesmo um bug que escapasse à
validação encontraria o filesystem em somente leitura fora dos caminhos
declarados.

Instalação:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now zordon
systemctl status zordon
journalctl -u zordon -f
```

## 4. Autostart no Windows

Duas tarefas agendadas, criadas pelo MSI, ambas no logon do usuário:

| Tarefa | Ação | Por quê |
|---|---|---|
| `Zordon WSL Boot` | `wsl.exe -d Ubuntu --exec /bin/true` | Acorda a VM para o systemd subir — [R1](../architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows) |
| `Zordon Host` | `zordon-host.exe` | Áudio, bridge e supervisor |

Ambas: "Executar apenas quando o usuário estiver conectado", sem janela, sem
condição de energia (senão não roda no notebook na bateria), com repetição
desabilitada.

```powershell
# equivalente manual, para diagnóstico
schtasks /Create /TN "Zordon WSL Boot" /TR "wsl.exe -d Ubuntu --exec /bin/true" ^
         /SC ONLOGON /RL LIMITED /F
```

O `zordon-desktop` **não** é autostart. O host basta; a interface abre quando o
usuário quiser.

## 5. Estrutura de diretórios

```text
WSL — /home/<user>/
├── .local/share/zordon/       binários e libs
│     bin/zordon-core
│     lib/*.jar
└── .zordon/
      config.toml              configuração principal
      mcp.toml                 servidores MCP
      apps.toml                catálogo de aplicações conhecidas
      pricing.toml             tabela de preços por modelo
      tool-overrides.toml      ajustes de risco por ferramenta
      agents/*.toml            definições de agentes
      automations/*.toml       automações
      zordon.db                memória + auditoria   ← ext4, nunca /mnt/c
      models/                  ONNX e vozes
      venv/                    ambiente Python do sidecar
      logs/                    core.jsonl rotacionado
      run/voice.sock           IPC com o sidecar
      secrets.age              fallback cifrado (se não houver Credential Manager)
      security-policy.toml     política de segurança  ← somente leitura em execução
      vault/                   quarentena: payload + manifest + evidências
      baseline.db              linha de base dos detectores

Windows
├── %LOCALAPPDATA%\Programs\Zordon\     zordon-desktop.exe, zordon-host.exe
├── %LOCALAPPDATA%\Zordon\              estado da UI, cache, logs do host
└── %USERPROFILE%\.zordon\
      endpoint.json            escrito pelo núcleo  ← única escrita rotineira em /mnt/c
```

## 6. Configuração

`~/.zordon/config.toml`, recarregável a quente exceto onde indicado:

```toml
[core]
port = 8777
bindAddress = "auto"          # auto = 127.0.0.1 em mirrored, 0.0.0.0 em nat
logLevel = "INFO"

[ai.roles]
# ver 09 §9.1

[ai.budget]
perTurn = "USD 0.50"
perDay  = "USD 10.00"
perMonth = "USD 100.00"

[permissions]
yellowDefault = "ask"         # ask | allow | deny
redTimeout = "PT60S"
autoAllowKnownApps = true

[paths]
# Atenção: o usuário do WSL e o do Windows podem ter nomes diferentes.
workspaces = ["D:/projetos", "/home/<user>/dev"]
readable   = ["D:/", "C:/Users/<user>/Documents"]
forbidden  = ["C:/Windows", "C:/Program Files", "**/.git/**", "**/.ssh/**",
              "**/node_modules/**", "**/.env", "**/*.pem", "**/*.key",
              "~/.zordon/security-policy.toml", "~/.zordon/vault/**"]

[voice]
mode = "wake"
wakeWord = "zordon"
sensitivity = 0.6
sttModel = "small"
sttDevice = "adaptive"        # always | never | adaptive
ttsVoice = "pt_BR-faber-medium"
listenTimeout = "PT6S"

[monitor]
enabled = true
sampleHz = 1.0
idleSampleHz = 0.2

[defense]
enabled = true
rings = ["ai", "host"]          # "ai" sozinho já é útil; "host" exige linha de base
baselineDays = 7
autoContainment = true          # contenção reversível sem perguntar, em CRITICAL
lockdownOnIntegrityFailure = true

[notify]
criticalSoundEnabled = true
criticalVoiceEnabled = true
maxCriticalInterruptionsPerHour = 3
dailyDigestAt = "09:00"
```

### `~/.zordon/security-policy.toml`

Arquivo **separado e deliberadamente inconveniente de alterar**. Ele define os
tetos que nem o usuário consegue mudar pela conversa: caminhos proibidos
absolutos, ações que nunca são automatizáveis, capacidades concedidas por sujeito
e seus prazos.

É lido na inicialização e mantido imutável em memória. **Não existe método ZWP,
Skill ou ferramenta que o altere** — está em `forbidden`, o diretório é
somente-leitura no systemd, e alterá-lo exige editar o arquivo fora do Zordon e
reiniciar o serviço. Ver
[Defesa §2](../security/defense.md#2-zero-trust-aplicado).

Alterações em `[core].port`, `[core].bindAddress` e no endurecimento do systemd
exigem reinício do serviço; o resto é aplicado a quente com evento
`CONFIG_RELOADED`.

## 7. Backup

| O quê | Frequência | Onde |
|---|---|---|
| `zordon.db` | Diário (e antes de toda migração) | `~/.zordon/backups/`, 7 cópias |
| Arquivos `.toml` | A cada alteração | Mesmo diretório, com sufixo de data |
| `security-policy.toml` | A cada alteração | Versionado; alteração gera entrada de auditoria |
| `vault/` | **Não é backup automático** | Cofre já é preservação; copie manualmente se for reinstalar |
| Segredos | Manual | Nunca em backup automático em claro |

Backup do SQLite usa a API online (`VACUUM INTO`), não cópia do arquivo — copiar
um banco com WAL ativo produz um backup inconsistente.

## 8. Diagnóstico

| Sintoma | Verificar |
|---|---|
| UI diz "CORE OFFLINE" | `systemctl status zordon`; `journalctl -u zordon -n 100` |
| Núcleo não sobe depois de reiniciar o Windows | A tarefa `Zordon WSL Boot` rodou? `wsl -l -v` mostra a distro `Running`? |
| UI não acha o núcleo | `%USERPROFILE%\.zordon\endpoint.json` existe e é recente? Os endpoints listados respondem? |
| Conecta e cai na hora | Token velho (núcleo reiniciou) — o cliente deve reler o endpoint; verificar código de fechamento `4401` |
| Navegador consegue conectar | **Bug grave.** Verificar rejeição de `Origin` e o header de token |
| Voz não funciona | `zordon-host` rodando? sidecar vivo (`~/.zordon/run/voice.sock`)? `system.health` diz o quê? |
| STT lento | `sttDevice` caiu para CPU? Unreal/jogo em execução? ([R14](../architecture/windows-wsl.md#r14--a-gpu-é-disputada)) |
| Custo alto | Diagnostics → `cache.hit_ratio`. Se baixo, há invalidador no prefixo ([Core §3](../specs/core/design.md#3-composição-de-contexto)) |
| Tudo lento e o disco em 100% | Algo está lendo `/mnt/c` em volume ([R9](../architecture/windows-wsl.md#r9--io-em-mntc-é-ordens-de-grandeza-mais-lento)) |
| `powershell.exe` falha de dentro do núcleo | Esperado. O caminho correto é o bridge via host ([R5](../architecture/windows-wsl.md#r5--wsl_interop-não-existe-dentro-de-um-serviço-systemd)) |
| Automações disparam em rajada | Salto de relógio após suspensão ([R7](../architecture/windows-wsl.md#r7--o-relógio-do-wsl-desvia-após-suspensão)); verificar `catchUp` |
| Caminho do Windows não resolve | Usuário Windows ≠ usuário WSL. Confira `powershell.exe -Command '$env:USERPROFILE'` |
| Zordon em lockdown e não sai | Por design: só o usuário sai, pela UI. Ver o motivo na tela de Segurança |
| Agente parou de funcionar | Disjuntor aberto — `security.breakers` ou tela de Segurança |
| Alertas demais | `zordon.notify.dismissed_without_reading`; ajustar severidade, não desligar ([Comunicação §7](../security/communication.md#7-anti-fadiga)) |
| "Sumiu um arquivo" | O Zordon não apaga. Procure na quarentena: `security.quarantine.list` |

Comandos úteis:

```bash
systemctl status zordon
journalctl -u zordon -f
journalctl -u zordon --since "10 min ago" | grep ERROR

wslinfo --networking-mode          # nat | mirrored
ip -4 addr show eth0               # IP atual (muda em nat)
ss -tlnp | grep 8777               # o núcleo está escutando?

cat ~/.zordon/logs/core.jsonl | jq 'select(.turnId=="t_91a")'
sqlite3 ~/.zordon/zordon.db "SELECT ts,tool,decision,status FROM audit ORDER BY id DESC LIMIT 20;"

# defesa
ls ~/.zordon/vault/                               # itens em quarentena
jq . ~/.zordon/vault/*/manifest.json              # por que cada um está lá
sqlite3 ~/.zordon/zordon.db \
  "SELECT ts,severity,detector,outcome FROM audit WHERE detector IS NOT NULL
   ORDER BY id DESC LIMIT 20;"

# a consulta que prova a invariante: ação executada sem notificação ao usuário
sqlite3 ~/.zordon/zordon.db \
  "SELECT count(*) FROM audit WHERE status='ok' AND actor LIKE 'defense%'
   AND user_message_id IS NULL;"                  # deve ser SEMPRE 0
```

A última consulta é a verificação manual da invariante de
[ADR-0014](../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md). Qualquer resultado
diferente de zero é um bug de severidade máxima.

## 9. Atualização

1. Parar: `sudo systemctl stop zordon` e encerrar `zordon-host`.
2. Backup do `zordon.db`.
3. Instalar o MSI (Windows) e rodar `install.sh` (WSL).
4. Subir: `sudo systemctl start zordon`. Migrações rodam na inicialização; se
   falharem, o núcleo **não sobe** e o erro fica no journal.
5. Verificar `system.health` e a integridade da cadeia de auditoria.

Sempre atualizar os dois lados juntos. O ZWP tolera versões diferentes dentro da
mesma versão maior, mas isso é uma rede de segurança, não um modo de operação.

## 10. Desinstalação

```bash
sudo systemctl disable --now zordon
sudo rm /etc/systemd/system/zordon.service
rm -rf ~/.local/share/zordon

# ~/.zordon preserva memória, auditoria, política e QUARENTENA.
# Antes de remover, restaure ou copie o que estiver no cofre:
ls ~/.zordon/vault/
```

**Restaure a quarentena antes de desinstalar.** Arquivos no cofre são arquivos
reais do usuário que o Zordon moveu para proteger; removê-los junto com o Zordon
seria a única forma de ele acabar destruindo dado — exatamente o que
[ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md) existe para
evitar. O desinstalador avisa e recusa remover o cofre sem confirmação explícita.

No Windows: desinstalar pelo MSI (remove as tarefas agendadas) e apagar
`%LOCALAPPDATA%\Zordon` e `%USERPROFILE%\.zordon`.
