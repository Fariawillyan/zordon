---
document: spec-025
module: automation
section: spec
version: 1
updatedAt: 2026-09-19
securityLevel: restricted
tags: [spec,automacao,scheduler,watchers,workflow,notificacao,m6]
specId: SPEC-025
---

# SPEC-025 — Automações

| Campo | Valor |
|---|---|
| **Status** | IMPLEMENTING |
| **Owner** | Willyan Faria |
| **Agente responsável** | `AutomationAgent` |
| **Revisores** | SecurityAgent, ArchitectureAgent |
| **Marco** | M6 |

Aprovação delegada pelo owner em 2026-09-19, durante a ausência dele: "faça
todos os M? porque nao estarei aqui para dizer para avançar". Revisão humana
pendente para quando ele voltar.

## 1. Objetivo

O Zordon age quando ninguém está olhando:
- verifica a API a cada 10 minutos (UC6);
- avisa quando o build termina (UC7);
- nota o container que caiu às 3h, com a tela fechada (UC9).

Toda automação é aprovada na tela antes de existir, e nunca executa nada que
precise de alguém para autorizar ([Automação](design.md)).

## 2. Problema

Nada roda sem um pedido na hora. Um núcleo residente que não age sozinho é só um
chat num processo que não fecha.

## 3. Escopo

**Definição** (`~/.zordon/automations/<id>.toml`, escrita pelo Zordon só depois
da aprovação)

```toml
id = "api-health"
name = "Verificar a API a cada 10 minutos"
enabled = true
once = false                     # true: desliga depois do primeiro disparo que termina

[trigger]
type = "interval"                # interval | schedule | event | condition
every = "PT10M"
catchUp = "SKIP"                 # SKIP | ONCE | ALL (ALL com teto de 10)

[[step]]
id = "check"
tool = "http.check"
args = { url = "http://localhost:8080/health" }

[[step]]
id = "tell"
when = "check.status != 200"
notify = { title = "API fora do ar", body = "{{check.text}}", severity = "warning" }
```

**Gatilhos**

| Tipo | Campos | Semântica |
|---|---|---|
| `interval` | `every` (≥ 1 min), `catchUp` | Relógio monotônico. O último disparo fica gravado para a recuperação depois de uma queda |
| `schedule` | `cron` (5 campos), `zone`, `catchUp` | Cron com fuso |
| `event` | `event` (tipo do barramento), `match` (campos iguais) | Ex.: `CONTAINER_EVENT` com `{action = "die", container = "api"}` |
| `condition` | `metric` (`cpu`, `mem`, `disk`, `load`), `above`, `rearmBelow`, `sustainedFor`, `cooldown` (padrão 15 min) | Histerese, duração mínima e janela de silêncio ([Automação §4](design.md#4-histerese-e-deduplicação)) |

- Eventos de segurança (tópico `security`) não disparam automação.

**Workflow** (declarativo, sem laços nem código)
- Tipos de passo:
  - **ferramenta:** `tool` e `args`;
  - **agente:** `agent` e `task`;
  - **aviso:** `notify {title, body, severity}`.
- `when`: `ref op literal` (`==`, `!=`, `>`, `<`, `>=`, `<=`) ou só `ref`.
  - `ref` é `passo.campo` ou `event.campo`.
- Interpolação `{{passo.campo}}` e `{{event.campo}}`. Os campos de um passo são
  `text`, `status`, `ok` e os dados da ferramenta.
- `retry = {attempts, backoff}` e `onError = "stop" | "skip" | "notify"`. O
  padrão é parar e avisar.
- **Execução durável:**
  - cada disparo é uma tarefa no `TaskStore` (origem `automation:<id>`), e cada
    passo é uma etapa;
  - um passo concluído não roda de novo: a chave é `(automação, disparo, passo)`;
  - **a definição aprovada é gravada junto do disparo**, e a retomada usa ela:
    editar a automação depois não muda uma execução que já estava em curso;
  - uma queda deixa a execução `blocked` (SPEC-023), e o `task.resume` continua
    do passo seguinte ao último concluído.

**Segurança** ([Automação §7](design.md#7-segurança-de-automações))
- **Ator:** o motor recebe `automation:<id>`, com origem `AUTOMATION` e sem
  usuário presente. Com isso, toda ação sobe um nível.
- **RED nunca executa**, nem com aprovação prévia. Uma ferramenta YELLOW sobe
  para RED, então **só ferramentas GREEN rodam em automação**, dentro do escopo
  aprovado (as ferramentas listadas no workflow).
- **Passo de agente:** teto GREEN, orçamento do agente, e um teto diário de 200
  mil tokens somando todas as automações.
- **Criação:**
  - a proposta chega pela ferramenta `automation.propose`, que o modelo usa ou
    o desktop chama;
  - ela é validada: ferramenta que existe e é GREEN, agente que existe, `when`
    que se lê, gatilho válido;
  - fica pendente e sai um aviso na tela;
  - **só `automation.approve`, do desktop, grava o arquivo.**
- **Desativação automática:** 20 falhas seguidas desativam a automação e avisam.
- **Lockdown:** nenhum disparo acontece; cada um que for pulado fica no log.
- **Exclusão não existe:** a automação é desativada. O arquivo continua, e
  reativar é um clique.

**Aviso com a tela fechada** ([Automação §8](design.md#8-notificação-com-a-ui-fechada))
- O passo `notify` grava na fila durável do `NotificationCenter` (SPEC-015).
- Com o host conectado, sai também uma notificação nativa do Windows
  (`windows.notify`, capacidade nova do host).
- Avisos iguais da mesma automação em 10 min são agrupados: o seguinte sai
  com "(N× desde HH:MM)".

**Ferramentas novas**
- `http.check {url}`:
  - GET, com 10 s de prazo e sem seguir redirecionamento;
  - GREEN para endereços locais ou da rede privada, YELLOW para os demais;
  - devolve `status`, `ms` e os primeiros 300 caracteres do corpo, como dado.
- `automation.propose`: visível ao modelo, GREEN, sem efeito (só propõe).

**ZWP**

| Método | Params | Retorno |
|---|---|---|
| `automation.list` | `{}` | `{automations[{id, name, trigger, enabled, lastFiredAt?, failures, reason?}], proposals[]}` |
| `automation.propose` | `{spec}` | `{proposalId, summary}` |
| `automation.approve` | `{proposalId}` | `{id}`; só de um desktop |
| `automation.reject` | `{proposalId}` | `{rejected}` |
| `automation.enable` / `automation.disable` | `{id}` | `{enabled}`; só de um desktop |
| `automation.run` | `{id}` | `{taskId}`: dispara agora, pela tela |

**Tela:** a seção "Automações" nos ajustes lista as automações e as propostas.
Tem "Aprovar", "Recusar" e "Ativar/Desativar".

**Evento:** `AUTOMATION_TRIGGERED {automationId, name, trigger}` e
`AUTOMATION_FINISHED {automationId, ok, summary}` (tópico `automation`).

## 4. Não escopo

- Ferramentas YELLOW em automação: só com uma política de "presença declarada"
  que ainda não existe.
- `inotify` e eventos de Git como gatilho.
- Edição de automação pela tela: hoje é desativar e propor de novo.

## 5. Arquitetura

```text
Scheduler (interval, schedule) ─┐
EventWatcher (barramento) ──────┼─► AutomationEngine ─► WorkflowEngine ─► SkillRuntime (automation:<id>, GREEN)
ConditionWatcher (amostrador) ──┘         │                    │         AgentRunner (teto GREEN)
                                          │                    └───────► notify ─► NotificationCenter + host
                                  automation_state (V003)    TaskStore (durável)
automation.propose ─► proposta ─► automation.approve (desktop) ─► ~/.zordon/automations/<id>.toml
```

## 6. Fluxo

UC9, container que cai às 3h:
1. `CONTAINER_EVENT {container: "api", action: "die", exitCode: 137}` casa com
   a automação `container-api`.
2. O workflow roda: `docker.logs {container: "api", tail: 50}` e depois
   `notify`, com o fim do log.
3. O host mostra a notificação do Windows. O desktop, mesmo fechado, recebe o
   aviso da fila quando abrir.

## 7. Interfaces

```java
public record AutomationSpec(String id, String name, boolean enabled, boolean once, Trigger trigger,
        List<Step> steps, Limits limits) {}
public final class AutomationEngine { void reload(); Optional<String> fire(String id, Map<String, Object> event); }
public final class WorkflowEngine { String run(AutomationSpec spec, Map<String, Object> event); void resume(String taskId); }
```

## 8. Eventos

`AUTOMATION_TRIGGERED` e `AUTOMATION_FINISHED` (tópico `automation`), além do
`TASK_STATE` de cada execução.

## 9. Dados

| Dado | Onde | Retenção |
|---|---|---|
| Definições | `~/.zordon/automations/*.toml` | Até o usuário mexer; o Zordon só cria arquivo novo |
| Estado (último disparo, falhas, desativada) | `zordon.db`: `automation_state` (V003) | Permanente |
| Tokens do dia das automações | `zordon.db`: `automation_tokens` (V004) | Por dia; sobrevive ao reinício |
| Execuções | `zordon.db`: tarefas da SPEC-023 | Permanente |

## 10. Segurança

- É a superfície mais perigosa: roda sozinha, repetidas vezes, e nasce de
  linguagem natural. Por isso só roda GREEN, só com aprovação na tela, e a
  auditoria registra o ator `automation:<id>`.
- O conteúdo que uma automação lê (logs, corpo de HTTP) é dado. Num passo de
  agente, entra marcado como dado.
- A proposta mostra, antes de aprovar:
  - o gatilho resolvido;
  - cada passo com a ferramenta e os argumentos;
  - o que cada aviso vai dizer.

## 11. Permissões

- Passos de ferramenta: pelo motor, com a origem `AUTOMATION`, sem usuário
  presente e com o escopo do workflow.
- `automation.approve`, `automation.enable` e `automation.disable`: só do
  desktop.

## 12. Observabilidade

- Log INFO a cada disparo (gatilho, automação, tarefa) e a cada disparo pulado
  (lockdown, execução anterior ainda rodando).
- `system.diagnostics.automation`: ativas, desativadas, propostas pendentes e
  tokens do dia.

## 13. Casos de erro

| Caso | Comportamento |
|---|---|
| TOML inválido | Ignorado, com o motivo em `automation.list` |
| Disparo com execução anterior em curso | Pulado, com log |
| Passo falha | Conforme o `onError`; 20 falhas seguidas desativam |
| Host desconectado | O aviso fica na fila; a notificação nativa não sai |
| Teto diário de tokens | Passo de agente falha, com o motivo |

## 14. Testes

- Gatilhos: intervalo com relógio de teste; `catchUp` SKIP, ONCE e ALL; cron com
  fuso; evento com `match`; condição com duração mínima, histerese e janela de
  silêncio.
- Workflow: `when`, interpolação, `retry`, `onError`, retomada sem repetir passo
  concluído.
- Segurança: ferramenta YELLOW recusada na proposta; ator `automation:<id>` na
  auditoria; lockdown pula; 20 falhas desativam; aprovação só pelo desktop.
- UC6, UC7 e UC9 ponta a ponta, com servidor HTTP local e `docker` falso.
- Host: `windows.notify` com a bandeja falsa.

### Evidências (2026-09-19)

- `AutomationTest` (8 testes, com motor de permissão, auditoria e `TaskStore`
  reais):
  - CA-1: a proposta não escreve nada; `approve` grava o TOML que volta a virar
    a mesma definição; id repetido é recusado; desativar não apaga o arquivo;
  - CA-2: `when` decide, a interpolação usa o resultado anterior, e cada passo
    fica gravado (inclusive o pulado);
  - CA-2 (retomada): a execução guarda a **definição do disparo**; depois de
    uma queda, ela continua com a definição original, sem repetir o passo
    concluído, e a mesma tarefa não é retomada duas vezes;
  - CA-3: o evento só casa com todos os campos do `match`, e um disparo com
    execução em curso é pulado;
  - CA-5: ferramenta YELLOW é recusada já na proposta; em lockdown nada dispara;
    20 falhas seguidas desativam e avisam;
  - CA-5 (orçamento): o teto diário de tokens sobrevive ao reinício, bloqueia o
    passo de agente e só vira no dia seguinte;
  - `retry` e `onError` (`stop`, `skip`, `notify`) se comportam como a tabela;
  - avisos iguais agrupam ("Falhou (2× desde 12:00)") e ficam na fila mesmo sem
    host.
- `TriggersTest` (4 testes): `catchUp` SKIP, ONCE e ALL com teto de 10; intervalo
  imune a salto de relógio; condição com duração mínima, histerese e janela de
  silêncio; cron com fuso.
- Pendente do owner: aprovar na tela a primeira automação de verdade (UC6 com a
  API dele), que é o teste de campo.

## 15. Critérios de aceite

- `CA-1` Dada uma proposta, então nada é gravado até a aprovação no desktop; a
  aprovação cria o arquivo e a automação passa a valer sem reiniciar.
- `CA-2` Dada "verifica minha API a cada 10 minutos", então a checagem roda no
  intervalo, sobrevive a reinício (conforme o `catchUp`) e avisa quando a API
  não responde 200 (UC6).
- `CA-3` Dado um evento que casa (build terminou, container morreu), então o
  workflow roda e o aviso chega na fila e, com o host conectado, como
  notificação do Windows (UC7, UC9).
- `CA-4` Dada uma condição de métrica, então ela só dispara depois da duração
  mínima, rearma abaixo do limiar de histerese e respeita a janela de silêncio.
- `CA-5` Dada uma automação, então ela nunca executa ação acima de GREEN, fica
  parada em lockdown e é desativada depois de 20 falhas seguidas, com aviso.

## 16. Impacto em outros módulos

- `zordon-memory`: migração V003 (`automation_state`).
- `zordon-core`:
  - pacote `automation`;
  - `SkillRuntime.invokeForAutomation`;
  - `http.check`;
  - `WindowsBridge.notify`;
  - a retomada da SPEC-023 delega as tarefas de automação.
- `zordon-host`: `windows.notify` pela bandeja do Windows.
- `zordon-desktop`: seção Automações.

## 17. Dependências

- [Automação — design](design.md) · [SPEC-024](SPEC-024-monitor-do-sistema.md) ·
  [SPEC-023](../agents/SPEC-023-planos-duraveis-e-verificacao.md) ·
  [SPEC-015](../security/SPEC-015-pedido-de-permissao-notificacoes-e-kill-switch.md)
