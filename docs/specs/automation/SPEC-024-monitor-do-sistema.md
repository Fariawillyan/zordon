---
document: spec-024
module: automation
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: internal
tags: [spec,monitor,metricas,docker,push,cpu,m6]
specId: SPEC-024
---

# SPEC-024 — Monitor do sistema

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `DevOpsAgent` |
| **Revisores** | ArchitectureAgent, SecurityAgent |
| **Marco** | M6 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

O núcleo passa a saber o estado da máquina sem que ninguém pergunte: métricas
amostradas, com frequência que se adapta, e eventos de containers por push. É a
fonte das automações de condição e de evento (SPEC-025) e, no M7, dos detectores
([Automação §5](design.md#5-coleta-sem-polling)).

## 2. Problema

Hoje as métricas só existem quando alguém chama `system.metrics`, e ninguém
fica sabendo que um container caiu às 3h (UC9).

## 3. Escopo

**Amostrador (`SystemSampler`)**
- Lê `/proc/stat` (CPU %), `/proc/meminfo`, `/proc/loadavg`, `statvfs` de `/` e
  `/proc/net/dev` (bytes/s).
- A frequência se adapta:
  - **1 Hz** enquanto há quem precise: uma automação de condição ativa, ou uma
    consulta `system.metrics` nos últimos 60 s;
  - **0,2 Hz** no resto do tempo.
- Guarda a última amostra. O `ConditionWatcher` (SPEC-025) mantém a própria
  janela.
- Métrica não entra no barramento a cada amostra: o trace não vira ruído. A tela
  consulta `system.metrics` quando está aberta.

**Eventos do Docker (`DockerEvents`)**
- Processo longo `docker events --format {{json .}} --filter type=container`,
  aberto pelo `ProcessRunner.start` com uma `Permit` GREEN (`monitor.docker`,
  ator `system:monitor`, `docker` do catálogo). É push, sem polling.
- Cada linha vira `CONTAINER_EVENT {container, image, action, exitCode?, at}`.
  As ações que interessam:
  - `start`, `die`, `stop`, `restart`, `oom`;
  - `health_status: healthy|unhealthy`.
- **Queda do stream:** nova conexão com espera de 5 s, 30 s e 5 min.
- **Sem `docker` no catálogo:** o monitor fica desligado, com o motivo em
  `monitor.status`.

**ZWP:**

| Método | Params | Retorno |
|---|---|---|
| `system.metrics` | `{}` | `{cpu, memUsedMb, memTotalMb, diskUsedGb, diskTotalGb, netRxKbps, netTxKbps, load, sampledAt, rateHz}` |
| `monitor.status` | `{}` | `{sampler{rateHz}, docker{state, reason?, events}}` |

**Orçamento de CPU:** medido com o núcleo em repouso. Critério: menos de 3% de
um núcleo, somando o núcleo e o motor de voz ([Visão §5](../../vision.md#recursos)).

## 4. Não escopo

- GPU (`nvidia-smi`), inotify de arquivos e de Git, serviços do Windows: entram
  quando uma automação ou um detector precisar.
- Módulo `zordon-monitor` separado: fica no pacote `zordon.core.monitor` até o
  M7, quando o `DetectionEngine` for o segundo consumidor.

## 5. Arquitetura

```text
/proc, statvfs ──► SystemSampler (1 Hz ou 0,2 Hz) ──► última amostra ──► system.metrics, ConditionWatcher
docker events ──► ProcessRunner.start (Permit GREEN, auditado) ──► DockerEvents ──► CONTAINER_EVENT ──► barramento
```

## 6. Fluxo

UC9: `docker events` entrega `die` de `api` com `exitCode 137`. Sai
`CONTAINER_EVENT {container: "api", action: "die", exitCode: 137}` no barramento,
e o `EventWatcher` da SPEC-025 casa a automação.

## 7. Interfaces

```java
public final class SystemSampler { Snapshot latest(); void demand(); int rateHz(); }
public record Snapshot(double cpu, long memUsedKb, long memTotalKb, double diskUsedGb, double diskTotalGb,
        double netRxKbps, double netTxKbps, double load, Instant at) {}
```

## 8. Eventos

`CONTAINER_EVENT {container, image, action, exitCode?, at}` no tópico `system`.

## 9. Dados

Nada persistido. A última amostra vive na RAM.

## 10. Segurança

- O stream do Docker passa pelo mesmo caminho auditado de qualquer processo.
  Ele só lê (`docker events`).
- O conteúdo dos eventos é dado. Nomes de container e imagem vão para o
  barramento, e nunca variáveis de ambiente nem rótulos.

## 11. Permissões

- `monitor.docker`: GREEN, ator `system:monitor`.
- `system.metrics` e `monitor.status`: leitura.

## 12. Observabilidade

- Log INFO ao mudar de frequência e ao abrir ou perder o stream do Docker.
- `system.diagnostics.monitor`.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| `/proc` ilegível | Métrica ausente na amostra; as outras seguem |
| Docker parado ou ausente | `docker.state = retrying` ou `unavailable`, com o motivo |
| Linha do stream inválida | Ignorada, com aviso |

## 14. Testes

- Amostrador com `/proc` falso: CPU pela diferença de duas leituras, memória,
  rede em bytes/s, adaptação da frequência.
- `DockerEvents` com um `docker` falso que emite linhas e sai: eventos
  publicados, filtro de ações e nova conexão depois da saída.
- Medição de CPU em repouso do serviço instalado.

### Evidências (2026-09-19)

- `MonitorTest` (3 testes):
  - CA-1: CPU pela diferença de duas leituras de `/proc/stat` (30% num `/proc`
    falso), memória, carga e rede em bytes/s; a frequência sobe com consulta
    recente ou condição ativa e volta a 0,2 Hz depois de 60 s;
  - CA-2: um `docker` falso emite `die` com `exitCode 137` e
    `health_status: unhealthy`; os dois viram `CONTAINER_EVENT`, o `exec_start` e
    a linha que não é JSON são ignorados, e `monitor.docker` aparece na
    auditoria com os argumentos exatos;
  - CA-3: o stream que termina é reaberto com espera, e três conexões seguidas
    entregam evento.
- **No serviço instalado (2026-09-19, 21h35):** o stream real do Docker conectou
  (`monitor.docker → allow (green, policy)`, "eventos do Docker: acompanhando").
- **CA-4, medido no serviço:**
  - núcleo em repouso: **0,80% de um núcleo** numa janela de 54 s (o motor de
    voz parado: 0,00%);
  - o detector da palavra, em escuta contínua, custa **1,7% de um núcleo** (0,99
    s de CPU para 59 s de áudio, em blocos de 20 ms como o host manda);
  - somando, a escuta ativa fica em **≈ 2,5%**, abaixo dos 3% da
    [Visão §5](../../vision.md#recursos). A medição com o microfone real ligado
    é do owner, no teste de campo.

## 15. Critérios de aceite

- `CA-1` Dado o núcleo em repouso, então o amostrador roda a 0,2 Hz e passa a
  1 Hz quando há condição ativa ou consulta recente.
- `CA-2` Dado um container que morre, então `CONTAINER_EVENT` chega ao
  barramento sem polling, pelo caminho auditado.
- `CA-3` Dado o stream do Docker caindo, então ele volta com espera, e o estado
  aparece em `monitor.status`.
- `CA-4` Dado o núcleo e o motor de voz em repouso, então o consumo somado fica
  abaixo de 3% de um núcleo.

## 16. Impacto em outros módulos

- `zordon-core`: pacote `monitor` e os métodos ZWP.
- `zordon-api`: `CONTAINER_EVENT`.

## 17. Dependências

- [Automação — design](design.md) · [SPEC-016](../security/SPEC-016-execucao-mediada-ferramentas-e-ponte-windows.md)
