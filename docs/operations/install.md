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

[general]
# Sem esta linha a distro — e o núcleo com ela — é desligada 15 s depois que o
# último terminal fecha. vmIdleTimeout, acima, só vale depois disso.
instanceIdleTimeout=-1
```

As duas linhas de ociosidade são necessárias, e não a mesma coisa:
`instanceIdleTimeout` desliga a **distro** (padrão 15 s), `vmIdleTimeout` desliga
a **VM** (padrão 60 s) depois que nenhuma distro está rodando. Só com as duas o
núcleo fica de pé sem terminal aberto —
[R1](../architecture/windows-wsl.md#r1--o-wsl-não-sobe-no-boot-do-windows).

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

Environment="JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50 -XX:+UseZGC"
# Fixado na instalação: o PATH do systemd não enxerga o JDK do usuário (SDKMAN)
# e cairia no java do sistema — nesta máquina, o 21, que não roda classes do 25.
Environment=JAVA_HOME=/home/<user>/.sdkman/candidates/java/25.0.2-tem
Environment=ZORDON_HOME=/home/<user>/.zordon
# Resolvido na instalação perguntando ao Windows (R21).
Environment=ZORDON_WINDOWS_HOME=/mnt/c/Users/<user>
# nat | mirrored, lido do .wslconfig na instalação. Decide onde o núcleo escuta.
Environment=ZORDON_NETWORKING_MODE=nat
# Só o caminho; o "-" deixa o serviço subir sem o arquivo.
EnvironmentFile=-/home/<user>/.zordon/secrets.env

# Endurecimento — o núcleo não precisa de nada disso
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=read-only
# Resolvido na instalação. NUNCA use %i aqui: o usuário Windows pode ter
# outro nome — foi o caso nesta máquina.
# "-": se o perfil do Windows não foi resolvido, o caminho não existe e o
# systemd recusaria subir.
ReadWritePaths=/home/<user>/.zordon -/mnt/c/Users/<user>/.zordon
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true

[Install]
WantedBy=multi-user.target
```

Esta é a forma instalada. A fonte é o template
[`packaging/wsl/zordon.service.template`](../../packaging/wsl/zordon.service.template),
cujas marcas `@…@` o [`install.sh`](../../packaging/wsl/install.sh) preenche; se os
dois divergirem, vale o template.

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

### Chave de API do provider

> **Provisório.** O destino da chave é o Credential Manager do Windows, via
> `zordon-host` ([Segurança §5](../security/model.md#5-segredos)). Até essa
> integração existir, ela fica num arquivo com modo `0600`, lido pelo systemd.

O `install.sh` pergunta de qual provider é a chave (dá para pular). Para
configurar ou trocar depois:

```bash
packaging/wsl/set-api-key.sh anthropic     # pede, valida, grava e reinicia o serviço
packaging/wsl/set-api-key.sh openai
packaging/wsl/set-api-key.sh --env GROQ_API_KEY   # qualquer outro; grava sem validar
packaging/wsl/set-api-key.sh --check       # confere todas as chaves conhecidas
```

Cada provider tem a sua linha em `secrets.env`, e trocar a chave de um preserva
as dos outros. O nome da variável precisa bater com a referência do
`config.toml` (`api_key = "env:GROQ_API_KEY"`).

O script:

- pede a chave **sem eco** — ela não passa por argumento, histórico do shell nem
  chat;
- para Anthropic e OpenAI, **valida antes de gravar**, com uma chamada que não
  gasta tokens (`GET /v1/models`) — uma chave recusada não chega ao disco;
- troca só a linha daquela variável, de uma vez (arquivo temporário + `mv`), com
  modo `600`;
- reinicia o serviço, se ele estiver rodando.

A unit já declara `EnvironmentFile=-~/.zordon/secrets.env`, então não há override
a criar. Sem chave o núcleo sobe normalmente: as rotas locais respondem, e as
demais mensagens voltam dizendo o que falta.

Por que desta forma:

| Alternativa | Problema |
|---|---|
| `Environment=ANTHROPIC_API_KEY=…` na unit | `/etc/systemd/system/` é legível por qualquer usuário, e `systemctl show` exibe o valor |
| Chave no `.env` do repositório | O `.env` é para desenvolvimento; o serviço não o lê, e ele mora onde o Git está |
| Chave num argumento de comando | Argumentos aparecem para qualquer usuário em `ps` e ficam no histórico |

O que este arquivo **não** protege, e o Credential Manager protegeria: a chave
fica em claro no disco, legível por qualquer processo rodando como o mesmo
usuário, e aparece no ambiente do processo Java (`/proc/<pid>/environ`). É a
mesma exposição de qualquer variável de ambiente — aceitável enquanto o Zordon
não lê arquivos por conta própria, e é por isso que o arquivo já está na lista
`forbidden` (§6) antes de o M3 dar ao Zordon acesso a arquivos.

**Revogar:** revogue no console do provider primeiro; trocar o arquivo sozinho não
invalida uma chave que possa ter vazado.

**Chave válida não basta:** a conta da API precisa de crédito. Sem ele, o chat
responde "A conta da API está sem crédito", e o remédio é o console do provider
(*Plans & Billing*), não o Zordon.

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
      config.toml              providers e papéis de IA (sem segredo)
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
      secrets.env              chave de API, modo 0600  ← provisório, ver §3
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

`~/.zordon/config.toml`. O instalador deixa um exemplo comentado
([`config.toml.example`](../../packaging/wsl/config.toml.example)) se o arquivo
ainda não existir, e nunca sobrescreve o do usuário.

**Implementado hoje: só `[ai.providers]` e `[ai.roles]`**, lidos na inicialização
([SPEC-004](../specs/core/SPEC-004-providers-configuraveis.md)). As demais seções
abaixo são o desenho dos próximos marcos. Uma chave escrita no arquivo é
recusada: ele guarda `env:NOME`, e o valor fica em `~/.zordon/secrets.env`.

O desenho completo, recarregável a quente exceto onde indicado:

```toml
[core]
port = 8777
bindAddress = "auto"          # auto = 127.0.0.1 em mirrored, 0.0.0.0 em nat
logLevel = "INFO"

# Providers: quem pode responder. Nunca a chave — só o NOME da variável.
[ai.providers.anthropic]
type    = "anthropic"
api_key = "env:ANTHROPIC_API_KEY"

[ai.providers.ollama]
type     = "openai-compatible"
base_url = "http://127.0.0.1:11434/v1"

# Papéis: quem responde o quê. fallback entra se a conversa falhar antes de responder.
[ai.roles]
conversation = { provider = "anthropic", model = "claude-opus-5", effort = "high" }
fallback     = { provider = "ollama",    model = "qwen2.5:7b" }

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
              "~/.zordon/security-policy.toml", "~/.zordon/vault/**",
              "~/.zordon/secrets.env", "~/.zordon/secrets.age", "~/.zordon/key"]

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
