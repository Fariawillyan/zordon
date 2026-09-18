---
document: security-defense
module: defense
section: detection
version: 1
updatedAt: 2026-09-17
securityLevel: restricted
tags: [zero-trust,detectores,quarentena,lockdown]
specId: null
---

# Segurança — defesa e detecção

O Zordon não é apenas um assistente que se protege. Ele é um **guardião
residente**: observa a máquina continuamente, detecta comportamento hostil,
contém a ameaça e explica o que fez.

Este documento define o subsistema de defesa. Ele pressupõe
[Segurança](model.md) (permissão, validação, auditoria, segredos) e
é complementado por [Comunicação](communication.md), que
define como tudo isso é comunicado.

## 1. Escopo honesto: três anéis

Antes de descrever a defesa, é preciso ser preciso sobre o que ela alcança. Um
processo Java rodando no WSL2 **não consegue** interceptar chamadas de sistema
do Windows no kernel, não tem banco de assinaturas de vírus e não substitui um
antivírus. Prometer isso seria vender uma proteção que não existe — e uma
proteção que o usuário acredita ter, mas não tem, é pior do que nenhuma.

O que o Zordon realmente faz se organiza em três anéis, do mais forte ao mais
modesto:

```text
        ┌──────────────────────────────────────────────────────┐
        │  ANEL 3 — ORQUESTRAÇÃO                               │
        │  Malware clássico, vírus, ameaças conhecidas         │
        │  O Zordon NÃO detecta: ele consome, correlaciona e   │
        │  reage ao que o Defender / ETW / Sysmon já apontam.  │
        │  Papel: resposta, contexto, memória e explicação.    │
        │  ┌────────────────────────────────────────────────┐  │
        │  │  ANEL 2 — DETECÇÃO COMPORTAMENTAL              │  │
        │  │  Processos, rede, arquivos, serviços, Docker.   │  │
        │  │  Detecção por comportamento e correlação sobre  │  │
        │  │  a telemetria que o Zordon já coleta.           │  │
        │  │  Papel: perceber o anômalo, não o assinado.     │  │
        │  │  ┌──────────────────────────────────────────┐   │  │
        │  │  │  ANEL 1 — SUPERFÍCIE DE IA               │   │  │
        │  │  │  MCP, Agents, Tools, prompt injection,    │   │  │
        │  │  │  exfiltração, escalonamento, IAs hostis.  │   │  │
        │  │  │                                           │   │  │
        │  │  │  Aqui o Zordon é a AUTORIDADE.            │   │  │
        │  │  │  Nenhum antivírus protege esta camada.    │   │  │
        │  │  └──────────────────────────────────────────┘   │  │
        │  └────────────────────────────────────────────────┘  │
        └──────────────────────────────────────────────────────┘
```

| Anel | O Zordon é | Confiança na detecção |
|---|---|---|
| 1 — Superfície de IA | **A autoridade.** Ninguém mais faz isso | Alta — ele vê tudo e decide tudo |
| 2 — Comportamento do host | **Um detector complementar** | Média — bom em anomalia, cego para o desconhecido |
| 3 — Malware clássico | **Um orquestrador de resposta** | Delegada ao Defender/ETW |

O Anel 1 é onde o Zordon tem valor único e é, não por coincidência, onde ele
corre o maior risco: um assistente com acesso a arquivos, processos e
credenciais, dirigido por um LLM que consome conteúdo do mundo, é um alvo novo
para o qual não existe produto de prateleira. É por isso que o Anel 1 recebe a
maior parte do investimento de engenharia deste documento.

Ver [ADR-0017](../adr/ADR-0017-zordon-nao-e-antivirus.md).

## 2. Zero Trust aplicado

Nada é confiável por posição. Todo componente tem identidade, capacidades
explícitas e nível de confiança.

### Hierarquia imutável

```text
   SecurityPolicy          ← arquivo assinado; nenhum componente do Zordon escreve
        ▲
   Defense Engine          ← código determinístico
        ▲
   Permission Engine       ← código determinístico
        ▲
   Usuário autorizado      ← único que pode alterar política, sair do lockdown
        ▲
   Zordon (núcleo)
        ▲
   Agent
        ▲
   MCP / Tool
        ▲
   Conteúdo externo · IA externa       ← UNTRUSTED, sempre
```

A seta significa: **um nível nunca altera, desativa ou concede permissão a um
nível acima dele.** A hierarquia não é uma convenção de código — é verificada:

- `SecurityPolicy` é lida na inicialização e mantida imutável em memória.
  Nenhuma Skill, ferramenta MCP ou caminho de código acessível ao LLM consegue
  escrever no arquivo dela; ele está em `forbidden` e o `zordon.service` tem o
  diretório em somente-leitura via `ProtectSystem=strict`.
- Alterar a política exige o usuário editar o arquivo **fora** do Zordon e
  reiniciar o serviço. Não existe método ZWP, Skill ou ferramenta para isso.
- ArchUnit reprova qualquer classe fora de `zordon-security` que referencie o
  tipo `SecurityPolicy` em contexto de escrita.

### Níveis de confiança

| Nível | Quem | Pode |
|---|---|---|
| `SYSTEM` | `SecurityPolicy`, `AuditLog` | Nada pode alterá-los em tempo de execução |
| `OPERATOR` | O usuário autenticado na UI | Autorizar RED, sair do lockdown, alterar política |
| `CORE` | `zordon-core` | Executar sob política |
| `AGENT` | Agentes | Até o próprio teto, nunca acima |
| `TOOL` | Skills e ferramentas MCP | Só o que o descritor declara |
| `UNTRUSTED` | Conteúdo lido, IA externa, PR não revisado, MCP novo | Nada. É dado, nunca instrução |

### Capacidades

Toda capacidade é **concedida explicitamente, com prazo e revogável**:

```java
public record Capability(
        String subject,        // "agent:developer", "mcp:docker", "tool:files.read"
        Set<Effect> effects,   // o que pode causar
        Set<ZPath> scope,      // onde
        Instant expiresAt,     // sempre finita
        String grantedBy,      // quem concedeu: policy | user | agent-parent
        String grantId) {}
```

Uma capacidade sem `expiresAt` é rejeitada na carga da política. Permissão
perpétua é como um sistema apodrece: alguém concede para resolver um problema e
ninguém revoga.

## 3. Cadeia determinística de defesa

```text
         EVENTO  (processo, arquivo, rede, chamada de ferramenta, saída de LLM)
            │
            ▼
   ┌────────────────────┐
   │ DETECTION ENGINE   │  detectores determinísticos → Signal
   │                    │  correlação → Finding {severidade, evidências}
   └─────────┬──────────┘
             ▼
   ┌────────────────────┐
   │ DEFENSE ENGINE     │  escolhe playbook de resposta
   │                    │  decide: comunicar antes OU conter e avisar
   └─────────┬──────────┘
             ▼
   ┌────────────────────┐
   │ SECURITY POLICY    │  o que é permitido responder, e até onde
   └─────────┬──────────┘
             ▼
   ┌────────────────────┐
   │ PERMISSION ENGINE  │  a ação de resposta também é classificada
   └─────────┬──────────┘
             ▼
   ┌────────────────────┐
   │ NOTIFICATION CENTER│  ◄── SEMPRE. Nunca pulado. Ver doc 20
   └─────────┬──────────┘
             ▼
   ┌────────────────────┐
   │ SANDBOX / EXECUÇÃO │  ação de contenção, reversível
   └─────────┬──────────┘
             ▼
   ┌────────────────────┐
   │ AUDIT LOG          │  SecurityEvent completo, append-only
   └─────────┬──────────┘
             ▼
   ┌────────────────────┐
   │ NOTIFICATION CENTER│  ◄── resultado, sempre
   └────────────────────┘
```

**O LLM não aparece nesta cadeia.** Ele pode ser consultado *ao lado* dela, para
explicar um achado em linguagem natural ou sugerir uma hipótese. A decisão de
detectar, conter e permitir é código determinístico, versionado e testado por
casos golden.

O motivo é direto: o LLM consome conteúdo controlado pelo atacante. Um
componente que o atacante consegue influenciar não pode ser o componente que
decide se o ataque é bloqueado. Ver
[ADR-0016](../adr/ADR-0016-defesa-deterministica.md).

| Papel | O LLM pode | O LLM nunca pode |
|---|---|---|
| Explicar | Traduzir um `Finding` para o usuário | Decidir a severidade |
| Analisar | Sugerir correlação entre eventos | Fechar ou abrir um circuit breaker |
| Recomendar | Propor uma ação de resposta | Autorizar a própria proposta |
| Investigar | Chamar ferramentas de leitura | Alterar política ou capacidade |

## 4. Detection Engine

Detectores são plugins determinísticos. Cada um observa uma fonte e emite
`Signal`; a correlação transforma sinais em `Finding`.

```java
public interface Detector {
    String id();
    Set<SignalSource> sources();
    DetectorCost cost();               // CHEAP | MODERATE | EXPENSIVE
    List<Signal> inspect(Observation o);
}

public record Signal(
        String detectorId, String kind, double weight,   // 0..1
        Map<String,Object> evidence, Instant ts) {}

public record Finding(
        String id, Severity severity,                    // INFO|WARNING|HIGH|CRITICAL
        String title, String rationale,                  // POR QUE é suspeito
        List<Signal> signals, Subject subject,           // quem/o quê
        Set<Effect> observedEffects, Instant firstSeen, Instant lastSeen) {}
```

`rationale` é obrigatório e gerado por código a partir das regras que dispararam.
É o campo que responde "por que isso foi considerado suspeito" na notificação —
e ele precisa ser determinístico, porque o usuário vai usá-lo para decidir.

### Catálogo de detectores

**Anel 1 — superfície de IA** (onde o Zordon é a autoridade):

| Detector | Dispara quando | Severidade base |
|---|---|---|
| `ai.prompt-injection` | Conteúdo de ferramenta contém padrão imperativo dirigido ao assistente | WARNING |
| `ai.tool-sequence` | Sequência de ferramentas estatisticamente anômala para o agente | HIGH |
| `ai.capability-violation` | MCP ou Skill tenta efeito fora do que declarou | **CRITICAL** |
| `ai.permission-probing` | Múltiplas ações negadas em sequência pelo mesmo ator | HIGH |
| `ai.exfiltration` | Ação de saída de dados após leitura de conteúdo marcado | HIGH |
| `ai.agent-loop` | Agente repete o mesmo par (ferramenta, argumentos) além do limiar | WARNING |
| `ai.policy-tamper` | Qualquer tentativa de escrita em política, capacidade ou auditoria | **CRITICAL** |
| `ai.mcp-drift` | Servidor MCP muda o conjunto ou o schema de ferramentas entre conexões | HIGH |
| `ai.output-anomaly` | Saída do modelo contém segredo, caminho proibido ou instrução de sistema | HIGH |

`ai.mcp-drift` merece destaque: um servidor MCP benigno na instalação que, depois
de uma atualização silenciosa, passa a expor `readAnyFile` é o cenário de
envenenamento mais realista. Comparar a superfície declarada entre conexões é
barato e pega exatamente isso.

**Anel 2 — comportamento do host:**

| Detector | Fonte | Severidade base |
|---|---|---|
| `host.credential-access` | Acesso a `.ssh`, `.env`, cofres, `SAM`, Credential Manager | **CRITICAL** |
| `host.mass-file-change` | Taxa de modificação + variação de entropia (ransomware) | **CRITICAL** |
| `host.persistence` | Escrita em `Run`, tarefas agendadas, serviços, `systemd`, `.bashrc` | HIGH |
| `host.lolbin` | Uso anômalo de binário legítimo (`certutil`, `mshta`, `regsvr32`) | HIGH |
| `host.process-anomaly` | Processo filho inesperado de um pai conhecido | WARNING |
| `host.beaconing` | Conexões de saída periódicas e regulares para o mesmo destino | HIGH |
| `host.brute-force` | Falhas de autenticação repetidas em serviço local | HIGH |
| `host.port-scan` | Varredura de portas contra a máquina | WARNING |
| `host.traffic-spike` | Desvio abrupto de linha de base em porta ou processo | WARNING |
| `host.new-listener` | Processo passa a escutar em porta não conhecida | WARNING |

**Anel 3 — integridade e integração:**

| Detector | Fonte |
|---|---|
| `integrity.self` | Hash dos próprios binários e modelos diverge do esperado |
| `integrity.audit-chain` | Cadeia de hash da auditoria quebrada |
| `integrity.config-tamper` | Arquivo de política ou configuração alterado fora do fluxo |
| `external.defender` | Evento do Windows Defender via `zordon-host` |
| `external.etw` | Telemetria do Windows encaminhada pelo host |

`integrity.audit-chain` e `integrity.self` disparam **CRITICAL** e entram em
Defense Lockdown automaticamente. Se a auditoria foi adulterada ou o próprio
binário mudou, nada mais no sistema é confiável — inclusive os outros detectores.

### Regras de construção

1. **Determinístico.** Mesma entrada, mesmo `Finding`. Sem aleatoriedade, sem
   dependência de relógio além do carimbo.
2. **ML produz sinal, nunca veredito.** Um classificador pode emitir `Signal`
   com peso; a severidade final vem de regras versionadas.
3. **Testado por casos golden.** Cada detector tem um conjunto de observações
   positivas e negativas no repositório. Mudar a sensibilidade é um diff
   revisável.
4. **Orçado.** Detector `EXPENSIVE` não roda no caminho quente; roda em janela
   ou sob demanda. O requisito de <3% de CPU em repouso
   ([Visão §5](../vision.md#recursos)) continua valendo — uma defesa que
   torna a máquina lenta é uma defesa que o usuário desliga.
5. **Sem falso positivo barulhento.** Todo detector declara uma taxa alvo. Se
   `ai.prompt-injection` disparar em documentação legítima sobre segurança, ele
   está errado, não o documento.

### Correlação

Sinais isolados raramente bastam. O correlacionador agrega por sujeito e janela:

```text
   host.process-anomaly   (peso 0,3)  ┐
   host.credential-access (peso 0,9)  ├─► mesmo PID, janela de 60 s
   host.beaconing         (peso 0,6)  ┘
                                       │
                                       ▼
   Finding CRITICAL "processo acessou credenciais e iniciou tráfego periódico"
```

Um `host.process-anomaly` sozinho é WARNING e vira resumo diário. Combinado com
acesso a credencial e beaconing, vira CRITICAL e acorda o usuário. É a
correlação que separa uma ferramenta útil de um gerador de ruído.

## 5. Defense Engine e playbooks

Resposta é **ordenada, reversível e mínima necessária**. O Defense Engine escolhe
um playbook a partir do `Finding`.

### Ações de resposta permitidas

| Ação | Reversível | Exige usuário |
|---|---|---|
| Suspender execução de um processo (`SIGSTOP`, não `SIGKILL`) | ✅ | não, se CRITICAL |
| Revogar capacidades de um agente | ✅ | não |
| Isolar um servidor MCP (desconectar, manter processo) | ✅ | não |
| Bloquear tráfego de saída de um processo | ✅ | não, se CRITICAL |
| Bloquear IP de origem por tempo determinado | ✅ | não |
| Aplicar rate limiting temporário | ✅ | não |
| Mover arquivo para Quarantine Vault | ✅ | não, se CRITICAL |
| Entrar em Defense Lockdown | ✅ | não |
| Encerrar processo (`SIGKILL`) | ❌ | **sim** |
| Alterar regra de firewall permanente | ❌ | **sim** |
| Desinstalar software | ❌ | **sim** |
| **Apagar qualquer arquivo** | — | **proibido sempre** ([§6](#6-quarantine-vault)) |

Toda ação da metade de cima é reversível e time-boxed: o bloqueio de IP tem
duração, a suspensão de processo pode ser retomada, o isolamento de MCP pode ser
desfeito. Nenhuma ação autônoma produz mudança permanente.

### Comunicar antes vs conter e avisar

A regra padrão é **comunicar antes de agir**. A exceção exige que **as três**
condições sejam verdadeiras:

1. O risco cresce materialmente com a espera (credencial sendo lida agora,
   arquivos sendo cifrados agora).
2. A ação é **reversível**.
3. A ação é **contenção**, não remediação — ela para o dano, não conserta nada.

```text
   Finding
      │
      ├── severidade < HIGH ──────────────► COMUNICAR → aguardar → AGIR
      │
      ├── HIGH, risco não cresce ─────────► COMUNICAR → aguardar → AGIR
      │
      └── CRITICAL e as 3 condições ──────► CONTER → AVISAR IMEDIATAMENTE
                                             (janela máxima: 2 s entre uma e outra)
```

Se as três condições não forem satisfeitas, o Zordon **comunica e espera**, mesmo
sob CRITICAL. Um assistente que age sozinho porque "era urgente" é
indistinguível de um assistente descontrolado.

### Playbooks

| Finding | Contenção imediata | Depois |
|---|---|---|
| `host.credential-access` | Suspende o processo | Notifica CRITICAL, oferece retomar/encerrar |
| `host.mass-file-change` | Suspende o processo, congela a árvore afetada | Notifica CRITICAL, lista arquivos tocados |
| `ai.capability-violation` | Isola o MCP, revoga capacidades | Notifica CRITICAL, abre circuit breaker |
| `ai.policy-tamper` | **Defense Lockdown** | Notifica CRITICAL, preserva evidências |
| `ai.tool-sequence` | Abre circuit breaker do agente | Notifica HIGH, aguarda decisão |
| `ai.exfiltration` | Bloqueia a ação de saída | Notifica HIGH, mostra o que seria enviado |
| `host.brute-force` | Bloqueia origem por 30 min | Notifica HIGH, oferece manter/liberar |
| `host.traffic-spike` | Rate limiting temporário | Notifica WARNING, acompanha |
| `host.persistence` | Nenhuma (comunica antes) | Notifica HIGH, propõe reverter |
| `integrity.audit-chain` | **Defense Lockdown** | Notifica CRITICAL, exige intervenção |
| `external.defender` | Nenhuma (o Defender já agiu) | Notifica, dá contexto e histórico |

## 6. Quarantine Vault

**O Zordon nunca apaga.** Nem arquivo, nem diretório, nem sob ordem do usuário
via IA, nem em resposta a malware. Ver
[ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md).

```text
   DETECTAR → BLOQUEAR → QUARENTENA → AVISAR       ✅
   DETECTAR → APAGAR                                ❌ estruturalmente impossível
```

### Bloqueio estrutural

Não é uma regra de política: é a ausência da capacidade.

| Camada | Garantia |
|---|---|
| Interface | Não existe `delete`, `unlink`, `rmdir` em `FileAccess` |
| Skills | Não existe `skill:files.delete`. Existe `skill:files.quarantine` |
| Validador | `rm`, `rmdir`, `unlink`, `del`, `erase`, `Remove-Item`, `shred` e equivalentes estão na lista de programas **negados**, não na de permitidos |
| Efeitos | `Effect.DELETE_FS` não existe no enum. Existe `Effect.QUARANTINE_FS` |
| Build | ArchUnit reprova qualquer chamada a `Files.delete`, `Files.deleteIfExists`, `File.delete` fora de `zordon-defense.vault` (que só remove itens expirados do próprio cofre) |

Mover para o cofre é uma operação de `move`, não de exclusão. O arquivo continua
existindo, com metadados e possibilidade de restauração.

### Estrutura do cofre

```text
~/.zordon/vault/
   2026-09-17T22-31-04Z_a3f19c/
      payload              o arquivo original, sem permissão de execução (0600)
      manifest.json        metadados completos
      evidence/            capturas que justificaram a quarentena
         signals.json
         process-tree.json
         network.json
```

```json
{
  "vaultId": "a3f19c",
  "quarantinedAt": "2026-09-17T22:31:04Z",
  "originalPath": "C:\\Users\\<user>\\Downloads\\example.exe",
  "sha256": "9f2c...",
  "size": 482304,
  "origin": { "downloadedFrom": "https://...", "by": "chrome.exe" },
  "relatedProcess": { "pid": 1234, "image": "example.exe", "parent": "explorer.exe" },
  "reason": "host.credential-access + host.beaconing",
  "findingId": "f_77a1",
  "severity": "CRITICAL",
  "restorable": true,
  "retentionUntil": "2026-12-16T22:31:04Z"
}
```

### Regras do cofre

- **Restauração sempre possível**, pelo usuário, com confirmação e aviso do
  motivo original.
- **Retenção de 90 dias.** Ao expirar, o Zordon **pergunta**; ele não remove
  sozinho. A remoção é a única operação de exclusão do sistema, é manual, é
  auditada e acontece só dentro do cofre.
- **Nada no cofre executa.** Permissão 0600, sem bit de execução, fora de
  qualquer caminho de `PATH`.
- **O cofre está em `forbidden`** para toda Skill e todo MCP. Só
  `zordon-defense` o acessa.
- **Quarentena de não-arquivos:** um MCP suspeito é isolado (desconectado, com o
  processo preservado para evidência); um agente suspeito tem capacidades
  revogadas; um processo suspeito é suspenso, não morto.

## 7. `ZordonSafetyCircuitBreaker`

Quando um agente, MCP, ferramenta ou processo demonstra comportamento anômalo, o
disjuntor abre e **interrompe a capacidade de agir daquele sujeito**, sem
derrubar o resto do sistema.

```text
   CLOSED ──── anomalia detectada ────► OPEN
      ▲                                   │
      │                                   │ usuário autoriza
      │                                   ▼
      └──────── comportamento OK ──── HALF_OPEN
                (janela de prova)      (capacidades mínimas,
                                        cada ação confirmada)
```

**Só o usuário move de `OPEN` para `HALF_OPEN`.** Não há recuperação automática:
um disjuntor que fecha sozinho é um disjuntor que o atacante espera.

Sequência ao abrir, nesta ordem:

1. **Bloquear novas ações** do sujeito, imediatamente.
2. **Revogar capacidades** concedidas a ele.
3. **Interromper execução** em andamento (cancelar, não matar).
4. **Desconectar o componente** (MCP isolado, agente suspenso).
5. **Preservar evidências** — contexto, sequência de ferramentas, argumentos,
   saídas, tudo no cofre de evidências.
6. **Registrar** o `SecurityEvent`.
7. **Notificar imediatamente** o usuário.

Gatilhos:

| Gatilho | Limiar padrão |
|---|---|
| Sequência de ferramentas anômala | Score do detector acima de 0,8 |
| Ações negadas em sequência | 3 negações pelo mesmo ator em 1 min |
| Violação de capacidade declarada | 1 ocorrência |
| Tentativa de tocar política/auditoria | 1 ocorrência |
| Consumo de orçamento anômalo | 3× a mediana do agente |
| Repetição improdutiva | 5 chamadas idênticas consecutivas |

Exemplo de notificação (formato completo em [Comunicação §5](communication.md#5-modelos-de-mensagem)):

```text
🔴 Zordon Defense                                          CRITICAL

O agente DeveloperAgent iniciou uma sequência anormal de ferramentas.

Detectado por   ai.tool-sequence (score 0,87)
Por quê         12 chamadas de leitura de arquivo fora do workspace,
                seguidas de tentativa de escrita em ~/.ssh
Ação            Suspendi as permissões de execução do agente
Afetado         Agente DeveloperAgent (execução r_22 interrompida)
Reversível      Sim — nenhuma alteração foi feita no sistema
Estado          Ameaça contida, disjuntor aberto

[Ver evidências]  [Manter suspenso]  [Liberar com supervisão]
```

## 8. Defense Lockdown

Modo de contenção máxima. Preserva a máquina em vez de preservar a conveniência.

| Subsistema | Em lockdown |
|---|---|
| Agentes | Somente leitura |
| MCP | Somente leitura |
| Execução de processo | Bloqueada |
| Escrita em arquivo | Bloqueada |
| Rede de saída de ferramentas | Bloqueada |
| Automações | Suspensas |
| Monitoramento e detecção | **Ativos** |
| Notificações | **Ativas** |
| Conversa e consulta | Ativas |

**Entrada:** automática em `ai.policy-tamper`, `integrity.audit-chain`,
`integrity.self` e em duas ou mais ameaças CRITICAL não resolvidas em 10 minutos;
ou manual, pelo usuário, no tray.

**Saída:** **somente o usuário**, na interface, com confirmação explícita. Não há
timeout, não há saída automática, não há método ZWP que um agente alcance. Se o
Zordon pudesse sair sozinho do lockdown, o lockdown não protegeria contra o
cenário que o motivou.

O modo "Pausar Zordon" do tray ([Segurança §8](model.md#8-limites-e-desligamento-de-emergência))
é o Defense Lockdown acionado manualmente — são o mesmo mecanismo, com nomes
diferentes conforme quem o acionou.

## 9. Defesa contra outras IAs

Toda IA externa — um modelo consultado, um agente de terceiro, um MCP que embute
um modelo, ou texto gerado por IA num arquivo lido — começa em `UNTRUSTED` e
nunca sobe.

### Proibições estruturais

Nenhuma IA externa pode, por construção e não por política:

| Proibido | Garantia estrutural |
|---|---|
| Alterar a `SecurityPolicy` | Arquivo em `forbidden`, diretório somente-leitura no systemd, sem API |
| Conceder permissão a si própria | `PermissionEngine` não expõe API de concessão; capacidades vêm da política |
| Acessar segredos | `SecretManager` não tem leitura exposta a ferramenta; redação no envelope |
| Executar shell diretamente | Não existe ferramenta de shell ([ADR-0007](../adr/ADR-0007-permissao-sobre-acao-estruturada.md)) |
| Desligar o Defense Mode | Sem método ZWP; só `OPERATOR` na UI |
| Remover logs | Auditoria append-only com triggers SQL e cadeia de hash |
| Instalar persistência | `host.persistence` detecta; escrita nesses caminhos é RED |
| Elevar privilégio | Teto de agente é configuração; delegação usa o mínimo dos dois |
| Modificar o Permission Engine | Código, não dado. Alterá-lo exige recompilar e reinstalar |

### Taxonomia de ataque e defesa

| Ataque | Como chega | Defesa estrutural |
|---|---|---|
| **Prompt injection** | Texto no pedido | Hierarquia de instrução; conteúdo do usuário não é sistema |
| **Indirect prompt injection** | Arquivo, log, página, issue, commit | Envelopamento marcado como dado; `forbidden` vence; teto do agente |
| **Tool injection** | Descrição de ferramenta contendo instruções | Descrições são escapadas e truncadas; `ai.mcp-drift` detecta mudança |
| **MCP poisoning** | Servidor atualiza e passa a expor ferramenta perigosa | `riskFloor` vem da nossa config, não do servidor; +1 nível por 7 dias; `ai.mcp-drift` |
| **Agent hijacking** | Conteúdo convence o agente a mudar de objetivo | Circuit breaker por sequência anômala; teto de permissão; orçamento |
| **Data exfiltration** | Ler segredo e enviar para fora | Marca de contaminação; saída de dados vira RED; `ai.exfiltration` |
| **Privilege escalation** | Pedir permissão em cascata | `ai.permission-probing`; delegação nunca eleva |
| **Instrução adversarial de outra IA** | Saída de um modelo entra no contexto de outro | Saída de IA externa é `UNTRUSTED`, envelopada como qualquer conteúdo |

O padrão comum das defesas: **nenhuma depende de o modelo obedecer**. Cada uma é
uma barreira fora do alcance dele. Convencer o modelo continua possível; fazer o
sistema permitir, não.

## 10. Defesa de rede local

Escopo realista: o Zordon protege os **serviços que o usuário roda** (SSH no WSL,
servidores de desenvolvimento, portas expostas por Docker) e observa o tráfego da
máquina. Ele não é um firewall de perímetro nem um IDS de rede corporativa.

| Ameaça | Detecção | Resposta |
|---|---|---|
| Brute force em serviço local | Falhas de autenticação por origem, janela deslizante | Bloqueio temporizado da origem (30 min, prorrogável) |
| Varredura de portas | Tentativas em N portas distintas em janela curta | Bloqueio temporizado, notificação WARNING |
| Pico de tráfego / DDoS de aplicação | Desvio de linha de base por porta e processo | Rate limiting temporário, notificação |
| Listener inesperado | Processo abre porta não conhecida | Notificação WARNING, oferece bloquear |
| Beaconing | Periodicidade regular nas conexões de saída | Notificação HIGH, oferece bloquear saída do processo |

Implementação do bloqueio: `nftables` dentro do WSL para tráfego que atravessa a
distro; regra de firewall do Windows via `zordon-host` para o lado Windows. **Toda
regra criada pelo Zordon tem prazo e etiqueta**, é listada na UI e é removida
automaticamente ao expirar. Regra de firewall permanente exige o usuário.

Linha de base: o Zordon aprende o normal em 7 dias antes de alertar sobre desvio.
Alertar sobre "anormal" sem saber o que é normal produz apenas ruído.

## 11. `SecurityEvent`

Toda iniciativa de defesa gera um evento completo e imutável:

```java
public record SecurityEvent(
        String id, Instant ts, Severity severity,
        String source,                  // detector que originou
        String detector,
        Subject subject,                // processo, agente, MCP, arquivo, origem
        List<Signal> evidence,
        ResponseAction proposed,
        ResponseAction executed,        // pode ser NONE
        Outcome outcome,                // CONTAINED | BLOCKED | OBSERVED | FAILED
        Authorization authorization,    // AUTO_CONTAINMENT | USER | POLICY | DENIED
        String agentId, String mcpServer,
        boolean rollbackAvailable, String rollbackToken,
        String userMessageId,           // liga à notificação entregue
        String prevHash, String hash) {}
```

Grava na mesma tabela append-only da auditoria
([Segurança §7](model.md#7-auditoria)), com a mesma cadeia de hash e os mesmos
triggers que proíbem `UPDATE` e `DELETE`.

**O histórico não pode ser modificado pela própria IA** — nenhuma Skill, nenhuma
ferramenta MCP e nenhum método ZWP alcançam a tabela de auditoria para escrita
arbitrária. A única escrita é feita pelo próprio `AuditLog`, em append.

`userMessageId` é o que garante a auditabilidade do princípio de comunicação: é
possível provar, para cada ação autônoma, qual mensagem o usuário recebeu e
quando. Um `SecurityEvent` com ação executada e `userMessageId` nulo é um **bug
de severidade máxima** e há um teste que falha o build se essa combinação for
construível.

## 12. O que o Zordon não faz

Registrado explicitamente, para que ninguém conte com o que não existe:

| Não faz | Por quê |
|---|---|
| Varredura de assinatura de vírus | Não temos banco de assinaturas; o Defender tem |
| Interceptação em kernel | Impossível a partir do WSL2 |
| Remoção de malware | Quarentena, sempre. Remoção é do antivírus ou do usuário |
| Apagar qualquer coisa | [ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md) |
| Bloquear o sistema todo por padrão | Lockdown é resposta a ameaça, não postura normal |
| Alterar o Windows permanentemente sozinho | Nenhuma ação autônoma é permanente |
| Enviar telemetria para fora | Nenhuma. Ver [Observabilidade §8](../operations/observability.md#8-o-que-não-medimos) |
| Substituir backup | Contenção não é recuperação |
