---
document: spec-027
module: defense
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,defesa,resposta,disjuntor,anel2,securityevent,m7]
specId: SPEC-027
---

# SPEC-027 — Resposta, disjuntor e comportamento do host

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `DefenseAgent` |
| **Revisores** | SecurityAgent, ArchitectureAgent |
| **Marco** | M7 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

Um achado (SPEC-026) vira resposta **ordenada, reversível e mínima**: o sujeito
perde a capacidade de agir, o usuário é avisado com o motivo, e nada vira
permanente ([Defesa §5](../../security/defense.md#5-defense-engine-e-playbooks),
[§7](../../security/defense.md#7-zordonsafetycircuitbreaker)).

## 2. Problema

Hoje a defesa observa e avisa. Um servidor MCP que muda de superfície continua
conectado; um agente que tenta tocar a política continua tentando; e não existe
registro formal do que a defesa fez.

## 3. Escopo

**`SecurityEvent`** ([Defesa §11](../../security/defense.md#11-securityevent))
- Toda iniciativa de defesa grava um evento imutável, em cadeia de hash, na
  tabela `security_event` (V006), com os mesmos gatilhos que recusam `UPDATE` e
  `DELETE`.
- **Invariante:** evento com ação executada **exige** `userMessageId`. O
  construtor recusa a combinação, e há teste que prova que ela não é
  construível.

**Disjuntor por sujeito (`ZordonSafetyCircuitBreaker`)**
- Sujeitos: agente, servidor MCP, ferramenta, ator de automação, processo.
- Estados: `CLOSED` → `OPEN` (por achado) → `HALF_OPEN` (**só o usuário move**)
  → `CLOSED` (depois da janela de prova sem novo achado).
- Ao abrir, nesta ordem:
  1. bloquear novas ações do sujeito (o motor passa a negar, `breakerOpen`);
  2. revogar capacidades (as ferramentas do sujeito saem do escopo);
  3. interromper a execução em curso (cancelar, nunca matar);
  4. desconectar o componente (MCP isolado; agente suspenso);
  5. preservar a evidência (o achado com os sinais);
  6. registrar o `SecurityEvent`;
  7. notificar o usuário.
- Em `HALF_OPEN`, cada ação do sujeito exige confirmação na tela.

**Playbooks** (do achado para a ação)

| Achado | Resposta |
|---|---|
| `ai.capability-violation`, `ai.policy-tamper` (CRITICAL) | Abre o disjuntor do sujeito e avisa |
| `ai.mcp-drift` (HIGH) | Isola o servidor MCP (desconecta, mantém o processo) e abre o disjuntor |
| `ai.permission-probing`, `ai.agent-loop`, `ai.exfiltration` (HIGH) | Abre o disjuntor do agente; sem agente, só avisa |
| `integrity.audit-chain`, `integrity.self` (CRITICAL) | Defense Lockdown automático e aviso |
| `host.credential-access` (CRITICAL) | Avisa. Suspender o processo só com `[defense] suspendProcesses = true` |
| Demais | Só o aviso da SPEC-026 |

- **Nada permanente, nada apagado.** Isolar, suspender e bloquear são
  reversíveis; encerrar processo, mexer em firewall e desinstalar continuam
  exigindo o usuário e **não** estão implementados.

**Anel 2 — o que dá para ver de dentro do WSL, sem root**

| Detector | Como | Severidade |
|---|---|---|
| `host.credential-access` | Varre `/proc/*/fd` a cada 2 s procurando descritor aberto em `~/.ssh`, `~/.aws`, `~/.gnupg`, `*.env` e `~/.zordon/config.toml`, fora da lista de processos conhecidos | CRITICAL |
| `host.persistence` | `WatchService` em `~/.bashrc`, `~/.profile`, `~/.config/systemd/user`, `~/.config/autostart` | HIGH |
| `host.new-listener` | `/proc/net/tcp` e `tcp6` a 0,2 Hz: porta em LISTEN que não existia | WARNING |

**Limites honestos, registrados:**
- Sem root não há `fanotify`/`auditd`: uma leitura rápida (abrir, ler, fechar em
  milissegundos) pode passar entre duas varreduras. O que a varredura pega é o
  processo que **mantém** o arquivo aberto.
- Credencial do lado Windows (SAM, Credential Manager), ETW e Defender ficam
  para quando o host tiver o canal de telemetria.
- `host.mass-file-change`, `host.beaconing`, `host.brute-force` e
  `host.port-scan` dependem de linha de base ou de captura de rede, e entram com
  ela — um detector que grita durante um build é um detector que o usuário
  desliga.

**ZWP**

| Método | Params | Retorno |
|---|---|---|
| `security.breakers` | `{}` | `{breakers[{subject, state, since, reason, findingId}]}` |
| `security.breakerRelease` | `{subject, mode}` | `{state}`; `mode` é `supervised` (HALF_OPEN) ou `closed`; só de um desktop |
| `security.events` | `{limit?}` | `{events[]}` — o histórico da defesa |

**Tela:** a seção Segurança ganha os disjuntores abertos, com o motivo e os dois
botões de liberação.

## 4. Não escopo

- Bloqueio de tráfego por processo e de IP de origem: dependem de firewall, que
  é mudança permanente no Windows.
- `SIGKILL`, desinstalação e regra de firewall: exigem o usuário e continuam
  fora.
- Resumo diário anti-fadiga e linha de base de 7 dias.

## 5. Arquitetura

```text
Finding (SPEC-026) ─► DefenseEngine ─► playbook ─► ações reversíveis
                           │                        ├─ breaker.open(subject)
                           │                        ├─ mcp.isolate(server)
                           │                        ├─ lockdown.enter(...)
                           │                        └─ (process.suspend, desligado por padrão)
                           ├─► SecurityEvent (append-only, com userMessageId)
                           └─► NotificationCenter (o aviso que o evento referencia)

/proc/*/fd, WatchService, /proc/net/tcp ─► detectores do Anel 2 ─► Observation ─► SPEC-026
```

## 6. Fluxo

UC12, servidor MCP muda de superfície:
1. `ai.mcp-drift` vira achado HIGH (SPEC-026).
2. O playbook isola o servidor: o cliente é desconectado e as ferramentas saem
   do registro.
3. O disjuntor de `mcp:<servidor>` abre.
4. Sai a notificação; o `SecurityEvent` referencia o `messageId` dela.
5. O servidor só volta quando o usuário liberar na tela.

## 7. Interfaces

```java
public record SecurityEvent(String id, Instant ts, Severity severity, String detector, String subject,
        String findingId, String proposed, String executed, String outcome, String authorization,
        boolean rollbackAvailable, String userMessageId) {
    public SecurityEvent {            // a invariante mora no construtor
        if (!"NONE".equals(executed) && (userMessageId == null || userMessageId.isBlank())) {
            throw new IllegalArgumentException("ação executada sem mensagem ao usuário");
        }
    }
}
public final class CircuitBreakers { State open(String subject, String reason, String findingId); boolean release(String subject, String mode); }
```

## 8. Eventos

`SECURITY_ACTION_TAKEN {eventId, subject, executed, outcome, reversible}` e
`CIRCUIT_BREAKER_OPENED {subject, reason, findingId}` /
`CIRCUIT_BREAKER_CLOSED {subject, by}` (tópico `security`).

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| `SecurityEvent` | `zordon.db`: `security_event` (V006), append-only com cadeia de hash | Permanente |
| Estado dos disjuntores | RAM + `SecurityEvent` de abertura | Até o usuário liberar |

## 10. Segurança

- **A defesa não usa modelo.** Playbook é tabela; ação é código.
- **Nenhuma ação autônoma é permanente** e nenhuma apaga nada (ADR-0015).
- **Comunicar antes é o padrão.** Conter antes de avisar só acontece com achado
  CRITICAL, e mesmo assim o aviso sai no mesmo instante — é a invariante do
  `userMessageId`.
- **O disjuntor não fecha sozinho:** um disjuntor que se fecha é um disjuntor que
  o atacante espera.

## 11. Permissões

- As ações da defesa são internas (isolar MCP, abrir disjuntor, lockdown) e não
  passam pelo motor de permissão — elas **reduzem** capacidade, nunca ampliam.
- Suspender processo, se ligado na configuração, passa pelo motor com origem
  `AUTONOMOUS` (só contenção reversível).
- `security.breakerRelease` é só do desktop.

## 12. Observabilidade

- Log WARN a cada ação de defesa, com sujeito e motivo.
- `system.diagnostics.defense.breakers` e a contagem de `SecurityEvent`.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| Playbook falha (MCP já caiu) | `SecurityEvent` com `outcome = FAILED`, aviso mantido |
| Sujeito desconhecido na liberação | `ERR_NOT_FOUND` |
| Varredura do `/proc` sem permissão | Detector segue com o que consegue ler; o motivo vai ao diagnóstico |

## 14. Testes

- Invariante: construir `SecurityEvent` com ação executada e sem
  `userMessageId` lança; o teste falha o build se a combinação passar.
- Disjuntor: abre por achado, bloqueia ação no motor, isola MCP, cancela
  execução, e só o usuário move para `HALF_OPEN` ou `CLOSED`.
- Playbooks: cada linha da tabela, com um achado sintético.
- Anel 2: processo de teste que abre `~/.ssh/id_rsa` é detectado; processo da
  lista conhecida não; arquivo de persistência alterado dispara; porta nova em
  LISTEN dispara.
- Cadeia: `security_event` recusa `UPDATE` e `DELETE`.

### Evidências (2026-09-19)

- `ResponseTest` (4 testes, módulo `zordon-defense`):
  - CA-5: `SecurityEvent` com ação executada e sem `userMessageId` **não
    compila em tempo de execução** — o construtor recusa, e o teste prova as
    duas formas (nulo e em branco);
  - disjuntor: abre, é idempotente, e só o usuário move para supervisionado ou
    fechado;
  - CA-4: processo desconhecido com `~/.ssh/id_rsa` aberto vira observação com
    PID e caminho; `ssh` não; um aviso por descritor aberto, e de novo quando
    reabre;
  - porta nova em LISTEN e arquivo de persistência alterado viram observação (a
    primeira varredura é só linha de base).
- `DefenseResponseTest` (3 testes, núcleo):
  - CA-1: efeito não declarado por um agente abre o disjuntor, cancela a
    execução, grava o `SecurityEvent` com o `userMessageId` da notificação
    entregue, e a **ação seguinte do agente é negada pelo motor**; liberar na
    tela devolve a capacidade;
  - CA-2: `ai.mcp-drift` isola o servidor e abre o disjuntor; a tabela
    `security_event` recusa `UPDATE` e `DELETE`;
  - CA-3: integridade quebrada entra em lockdown; achado sem playbook fica
    registrado como `OBSERVED`, sem abrir disjuntor.
- **Limites registrados:** sem root não há `fanotify`/`auditd`, então uma
  leitura de milissegundos pode escapar entre varreduras; credencial do lado
  Windows, ETW e Defender dependem do canal de telemetria do host; suspender
  processo existe como ação, mas fica **desligada** até o owner decidir.

## 15. Critérios de aceite

- `CA-1` Dado um achado CRITICAL de capacidade ou política, então o disjuntor do
  sujeito abre, a ação seguinte dele é negada pelo motor, e há `SecurityEvent`
  com `userMessageId` da notificação entregue.
- `CA-2` Dado `ai.mcp-drift`, então o servidor é isolado (desconectado, sem as
  ferramentas) e só volta com liberação na tela (UC12).
- `CA-3` Dado `integrity.audit-chain` ou `integrity.self`, então o Zordon entra
  em lockdown automaticamente e avisa.
- `CA-4` Dado um processo fora da lista conhecida com `~/.ssh/id_rsa` aberto,
  então sai achado CRITICAL com o PID e o caminho em menos de 3 s, e nada é
  apagado; a suspensão só acontece se o dono tiver ligado.
- `CA-5` Dado qualquer `SecurityEvent` com ação executada, então ele tem
  `userMessageId`; a construção sem ele é impossível.

## 16. Impacto em outros módulos

- `zordon-memory`: migração V006 (`security_event`).
- `zordon-defense`: `CircuitBreakers`, `SecurityEvent`, detectores do Anel 2.
- `zordon-core`: `DefenseEngine` ligando playbooks ao `McpManager`, ao
  `LockdownService` e ao `SkillRuntime` (negação por disjuntor).
- `zordon-desktop`: disjuntores na seção Segurança.

## 17. Dependências

- [SPEC-026](SPEC-026-deteccao-e-correlacao.md) ·
  [Defesa](../../security/defense.md) ·
  [ADR-0018](../../adr/ADR-0018-circuit-breaker-e-lockdown.md) ·
  [ADR-0015](../../adr/ADR-0015-exclusao-impossivel-por-construcao.md)
