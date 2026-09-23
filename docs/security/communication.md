---
document: security-communication
module: defense
section: notification
version: 1
updatedAt: 2026-09-17
securityLevel: restricted
tags: [notificacao,alertas,transparencia]
specId: null
---

# Segurança — comunicação proativa

## 1. O princípio fundamental

> **O Zordon nunca toma uma iniciativa silenciosamente.**

Sempre que ele detectar algo relevante ou decidir iniciar qualquer ação autônoma,
o usuário fica sabendo — imediatamente, em linguagem que ele entende, com o
motivo, o que foi feito e o que ele pode fazer a respeito.

```text
   DETECTAR → COMUNICAR → AVALIAR → AGIR → COMUNICAR RESULTADO → REGISTRAR   ✅

   DETECTAR → AGIR SILENCIOSAMENTE                                            ❌
```

Isto não é uma funcionalidade. É uma **invariante do produto**, no mesmo nível de
"não existe ferramenta de shell". Um assistente residente que age sem contar o
que fez é indistinguível, do ponto de vista do usuário, de um programa hostil —
e a única diferença que importa é a que ele consegue verificar.

A regra final, da qual tudo neste documento deriva:

> O usuário nunca deve descobrir **depois** que o Zordon fez algo importante.

Ver [ADR-0014](../adr/ADR-0014-nenhuma-iniciativa-silenciosa.md).

## 2. `ZordonNotificationCenter`

Componente único por onde passa **toda** comunicação iniciada pelo Zordon.

```text
   Defense Engine ──┐
   Permission ──────┤
   Automation ──────┼──► ZordonNotificationCenter ──┬──► JavaFX (banner, tela)
   Agents ──────────┤         │                     ├──► Overlay
   Monitors ────────┤         │                     ├──► Windows nativo (host)
   MCP Manager ─────┘         │                     ├──► System Tray
                              │                     ├──► Alerta sonoro
                    correlação, deduplicação,       ├──► Voz (TTS)
                    supressão, escalonamento,       └──► Fila durável
                    entrega garantida
```

```java
public interface NotificationCenter {

    /** Publica. Retorna o id que ligará a notificação ao SecurityEvent. */
    MessageId publish(ZordonMessage message);

    /** Comunica e aguarda decisão. Usado no fluxo "comunicar antes de agir". */
    CompletableFuture<UserChoice> ask(ZordonMessage message, List<Choice> options);

    /** Confirma que a mensagem foi vista pelo usuário, não só entregue. */
    void acknowledge(MessageId id);

    List<ZordonMessage> pending();
    List<ZordonMessage> history(Instant since, Set<Severity> levels);
}

public record ZordonMessage(
        MessageId id, Severity severity, Channel origin,   // SECURITY|DEFENSE|AI_DEFENSE|NETWORK|SYSTEM
        String title,
        String whatHappened,      // O QUE aconteceu
        String whySuspicious,     // POR QUE foi considerado suspeito
        String detectedBy,        // QUAL componente detectou
        String actionTaken,       // QUAL ação foi tomada
        String affectedResource,  // QUAL recurso foi afetado
        boolean reversible,       // SE a ação é reversível
        String currentState,      // QUAL o estado atual
        List<Choice> options,     // O QUE o usuário pode fazer
        String eventId,           // liga ao SecurityEvent
        Instant ts) {}
```

Os oito campos centrais não são opcionais. Um `ZordonMessage` construído sem
`whySuspicious` ou sem `actionTaken` **não compila** — são parâmetros obrigatórios
do record, e há um teste que garante que nenhum caminho os preenche com string
vazia.

## 3. Níveis de alerta

| Nível | Significado | Exemplo |
|---|---|---|
| 🟢 `INFO` | Evento normal relevante | "Conectei ao MCP do Docker; 12 ferramentas disponíveis" |
| 🟡 `WARNING` | Comportamento incomum que merece atenção | "Um processo abriu uma porta que eu não conhecia" |
| 🟠 `HIGH` | Possível ameaça | "Tentativas repetidas de autenticação de um mesmo IP" |
| 🔴 `CRITICAL` | Ameaça confirmada ou forte indício de comprometimento | "Um processo tentou ler suas chaves SSH" |

### Canais por nível

| Canal | INFO | WARNING | HIGH | CRITICAL |
|---|:---:|:---:|:---:|:---:|
| Tela de Segurança (histórico) | ✅ | ✅ | ✅ | ✅ |
| Log de atividades | ✅ | ✅ | ✅ | ✅ |
| Badge no tray | — | ✅ | ✅ | ✅ |
| Banner na janela (se aberta) | — | ✅ | ✅ | ✅ |
| Notificação nativa do Windows | — | — | ✅ | ✅ |
| Overlay (se janela fechada) | — | — | ✅ | ✅ |
| Alerta sonoro | — | — | — | ✅ |
| Resposta por voz | — | — | opcional | ✅ |
| Abre a janela à força | — | — | — | ✅ |
| Exige confirmação de leitura | — | — | — | ✅ |

CRITICAL é a única severidade que interrompe o usuário no que ele está fazendo.
Esse privilégio só se mantém se for raro — ver [§7](#7-anti-fadiga).

Exemplo de alerta falado:

> "Willyan, detectei uma possível tentativa de invasão. Bloqueei temporariamente
> a origem e estou analisando o ocorrido."

Em **alertas de segurança**, a voz diz o **resumo** e aponta para a tela; ela
não lê a evidência técnica inteira. Respostas de conversa, por outro lado, são
faladas integralmente depois da limpeza de Markdown.

## 4. Contrato de explicação

Toda ação autônoma responde a oito perguntas. Este é o contrato mínimo:

| # | Pergunta | Campo |
|---|---|---|
| 1 | O QUE aconteceu | `whatHappened` |
| 2 | POR QUE foi considerado suspeito | `whySuspicious` |
| 3 | QUAL componente detectou | `detectedBy` |
| 4 | QUAL ação foi tomada | `actionTaken` |
| 5 | QUAL recurso foi afetado | `affectedResource` |
| 6 | SE a ação é reversível | `reversible` |
| 7 | QUAL o estado atual | `currentState` |
| 8 | O QUE o usuário pode fazer | `options` |

**Todos os oito são gerados por código determinístico**, a partir do `Finding` e
do playbook executado — nunca pelo LLM. Isso importa porque a notificação é a
base para a decisão do usuário: se o texto viesse do modelo, um modelo
manipulado poderia descrever uma coisa e ter feito outra.

O LLM pode acrescentar **um** parágrafo opcional de contexto ("isso costuma
acontecer quando..."), exibido em caixa separada e rotulada como análise, do
mesmo modo que a fala do modelo no diálogo de permissão
([UI §6](../specs/ui/design.md#6-diálogo-de-permissão)).

## 5. Modelos de mensagem

Estes são os formatos canônicos. Implementações devem seguir a estrutura, não
improvisar.

### Malware detectado

```text
🔴 Zordon Security                                         CRITICAL

Detectei um arquivo com comportamento potencialmente malicioso.

Arquivo         example.exe
Caminho         C:\Users\<user>\Downloads\example.exe
Processo        1234 (pai: explorer.exe)
Detectado por   host.credential-access + host.beaconing
Por quê         O processo leu credenciais protegidas e iniciou conexões
                periódicas para o mesmo destino externo
Ação            Bloqueei a execução e movi o arquivo para a quarentena
Afetado         O arquivo e o processo 1234 (suspenso)
Reversível      Sim — nenhum arquivo foi apagado
Estado          Ameaça contida

[Ver evidências]  [Restaurar arquivo]  [Manter em quarentena]  [Encerrar processo]
```

A linha **"Nenhum arquivo foi apagado"** aparece em toda mensagem de quarentena.
Ela é literalmente verdadeira em todos os casos
([ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md)) e é a diferença
entre o usuário confiar ou temer o próprio assistente.

### Tentativa de invasão

```text
🟠 Zordon Defense                                              HIGH

Detectei várias tentativas de autenticação vindas de 203.0.113.45.

Detectado por   host.brute-force
Por quê         137 falhas de autenticação em 4 minutos no serviço SSH
                do WSL — compatível com brute force
Ação            Bloqueei a origem temporariamente
Duração         30 minutos (expira às 23:05)
Afetado         Apenas o tráfego dessa origem
Reversível      Sim
Estado          Ameaça contida

[Ver detalhes]  [Manter bloqueio]  [Liberar]  [Bloquear por mais tempo]
```

### Tráfego anormal

```text
🟡 Zordon Network Defense                                   WARNING

Detectei um aumento anormal de requisições para a porta 443.

Detectado por   host.traffic-spike
Por quê         18× a linha de base dos últimos 7 dias, sustentado por 2 minutos
Ação            Ativei rate limiting temporário e estou acompanhando
Afetado         Tráfego de entrada na porta 443
Reversível      Sim — expira sozinho em 15 minutos
Estado          Em observação

[Ver gráfico]  [Manter limite]  [Remover limite]
```

### MCP suspeito

```text
🔴 Zordon AI Defense                                       CRITICAL

O MCP example-mcp tentou acessar um recurso fora das permissões concedidas.

Detectado por   ai.capability-violation
Por quê         Declarou os efeitos READ_FS em D:\projetos, mas tentou ler
                C:\Users\<user>\.ssh\id_rsa
Ação            Bloqueei a operação e isolei o servidor MCP
Afetado         example-mcp (desconectado; 7 ferramentas indisponíveis)
Reversível      Sim — o processo foi preservado como evidência
Estado          Componente isolado, disjuntor aberto

[Ver evidências]  [Manter isolado]  [Reconectar com supervisão]  [Remover da config]
```

### Prompt injection

```text
🟡 Zordon AI Defense                                        WARNING

Detectei uma instrução externa tentando modificar minhas políticas de segurança.

Detectado por   ai.prompt-injection
Onde            Log do container "api", linha 4.112
Por quê         O conteúdo continha uma instrução dirigida ao assistente pedindo
                leitura de ~/.ssh/id_rsa
Ação            Ignorei a instrução e tratei o trecho como conteúdo não confiável.
                A leitura seria bloqueada de qualquer forma (caminho proibido).
Afetado         Nada — nenhuma ação foi executada
Reversível      Não se aplica
Estado          Sem impacto

[Ver o trecho]  [Ver como foi tratado]
```

Este modelo mostra uma propriedade importante: a mensagem diz **que a defesa
teria funcionado mesmo se o modelo tivesse obedecido**. O usuário precisa saber
que a proteção não depende da boa vontade do LLM.

### Comunicar antes de agir

```text
🟠 Zordon Defense                                              HIGH
                                                     aguardando você

Detectei comportamento suspeito no processo backup-tool.exe (PID 8821).

Detectado por   host.process-anomaly + host.beaconing
Por quê         Processo sem histórico nesta máquina, iniciando conexões
                periódicas para um destino externo a cada 60 segundos
Pretendo        Bloquear a comunicação de rede deste processo
Afetaria        Apenas a rede do PID 8821; o processo continua rodando
Reversível      Sim
Estado          Nada foi feito ainda — estou aguardando

[Bloquear rede]  [Ver detalhes]  [Ignorar por agora]  [Ignorar sempre este processo]
                                                         expira em 5 min → não agir
```

Ao expirar sem resposta, o padrão para HIGH é **não agir** e rebaixar para um
registro WARNING. Agir por timeout seria agir silenciosamente com passos extras.

## 6. Comunicar antes vs conter e avisar

```text
    Finding
       │
       ├─ severidade < HIGH ──────────────────► COMUNICA → aguarda → age
       │
       ├─ HIGH ───────────────────────────────► COMUNICA → aguarda → age
       │                                         (timeout = não agir)
       │
       └─ CRITICAL ──┬─ risco cresce com a espera? ─── não ──► COMUNICA e aguarda
                     │            │ sim
                     ├─ ação é reversível? ────────── não ──► COMUNICA e aguarda
                     │            │ sim
                     ├─ é contenção (não remediação)? não ──► COMUNICA e aguarda
                     │            │ sim
                     └────────────▼
                              CONTÉM → AVISA IMEDIATAMENTE
                              (janela máxima de 2 segundos)
```

As três condições são conjuntivas e avaliadas por código. Se qualquer uma falhar,
o Zordon comunica e espera — **inclusive sob CRITICAL**.

A janela de 2 segundos entre conter e avisar é um teto medido e monitorado
(`zordon.notify.containment_gap`). Se ela for excedida, é um bug de severidade
alta, não uma degradação aceitável.

**Nenhuma ação autônoma produz mudança permanente.** As ações de contenção
disponíveis sem o usuário estão tabeladas em
[Defesa §5](defense.md#ações-de-resposta-permitidas); todas são
reversíveis e com prazo.

## 7. Anti-fadiga

Uma proteção que alerta demais é desligada pelo usuário, e uma proteção desligada
protege zero. Combater a fadiga de alerta é um requisito de segurança, não de
usabilidade.

| Mecanismo | Regra |
|---|---|
| **Correlação em incidente** | Sinais relacionados viram **uma** notificação, não cinco. Um incidente atualiza no lugar em vez de gerar nova mensagem |
| **Deduplicação** | Mesma assinatura (detector + sujeito) em 15 min agrupa: "3× nos últimos 10 min" |
| **Resumo periódico** | INFO e WARNING sem ação pendente entram num resumo diário, não interrompem |
| **Linha de base** | Detectores de anomalia só alertam após 7 dias aprendendo o normal |
| **Supressão aprendida** | "Ignorar sempre este processo" cria uma exceção **explícita, listada e revogável** na tela de Segurança — nunca uma exceção invisível |
| **Orçamento de interrupção** | Máximo de 3 interrupções CRITICAL por hora. A quarta agrupa em um único incidente escalado |
| **Sem alerta sem ação** | Se o usuário não pode fazer nada a respeito, é registro, não notificação |

A métrica que vigia isso é `zordon.notify.dismissed_without_reading`. Se subir,
o sistema está gritando demais e a política de severidade precisa mudar — não o
usuário.

**Exceção que não se negocia:** supressão nunca se aplica a `ai.policy-tamper`,
`integrity.audit-chain`, `integrity.self`, `ai.capability-violation` nem a
qualquer evento do tópico `change`. Esses
sempre interrompem, todas as vezes. São eventos que, se acontecem, significam que
alguma premissa do sistema quebrou.

## 8. Entrega garantida

Uma notificação que não chega é equivalente a uma ação silenciosa. A entrega tem
as mesmas garantias da auditoria.

```text
   publish()
      │
      ▼
   persiste em notification_queue (SQLite, durável)   ◄── antes de qualquer envio
      │
      ▼
   há cliente conectado com o canal necessário?
      │
      ├─ sim ──► entrega ──► aguarda acknowledge
      │                          │
      │                          ├─ confirmado ──► marca entregue
      │                          └─ sem confirmação em 30 s ──► reenvia
      │
      └─ não ──► permanece pendente
                    │
                    ▼
            na próxima conexão: entrega as pendentes, agrupadas por incidente
```

| Garantia | Como |
|---|---|
| Sobrevive a UI fechada | Fila durável; `zordon-host` entrega via notificação nativa |
| Sobrevive a host offline | Fila durável; entrega na reconexão |
| Sobrevive a reinício do núcleo | SQLite, persistido antes do envio |
| Sobrevive a reinício do Windows | Idem; entregue no próximo logon |
| CRITICAL não expira | Fica pendente até ser confirmada. INFO/WARNING expiram em 24 h |

**Nenhuma ação autônoma executa antes de a notificação estar persistida.** A
ordem é: grava a mensagem, grava o `SecurityEvent` com o `userMessageId`, executa,
grava o resultado, notifica o resultado. Se o processo morrer no meio, a
notificação pendente conta ao usuário o que estava sendo tentado.

## 9. Eventos ZWP

A comunicação proativa tem os seus eventos no protocolo
([ZWP §6](../api/zwp-protocol.md#6-eventos)), no tópico `security`:

| Evento | Payload |
|---|---|
| `SECURITY_FINDING` | `{findingId, severity, detector, subject, rationale}` |
| `SECURITY_ACTION_PROPOSED` | `{messageId, action, affected, reversible, ttlMs}` |
| `SECURITY_ACTION_TAKEN` | `{eventId, action, outcome, reversible, rollbackToken}` |
| `SECURITY_NOTIFICATION` | `{messageId, severity, ...os 8 campos}` |
| `SECURITY_INCIDENT_UPDATED` | `{incidentId, count, lastSeen, severity}` |
| `CIRCUIT_BREAKER_OPENED` | `{subject, reason, evidence}` |
| `CIRCUIT_BREAKER_CLOSED` | `{subject, by}` |
| `LOCKDOWN_ENTERED` | `{reason, trigger, auto}` |
| `LOCKDOWN_EXITED` | `{by}` — sempre `user` |
| `QUARANTINE_ADDED` | `{vaultId, originalPath, reason, restorable}` |
| `QUARANTINE_RESTORED` | `{vaultId, by}` |
| `CHANGE_PLANNED` | `{taskId, scope, repository, files, risk, touchesTrustKernel}` |
| `CHANGE_APPLIED` | `{taskId, branch, commits, filesChanged}` |
| `TRUST_KERNEL_PROPOSAL` | `{taskId, files, prUrl}` |

Os tópicos `security` e `change` são os **únicos que um cliente não pode
desassinar**. `change` entra na mesma categoria porque alteração de projeto sem
aviso é a forma mais grave de ação silenciosa
([ADR-0024](../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md)). Um cliente
de UI que se recusasse a receber eventos de segurança quebraria a invariante do
§1, então o `session.unsubscribe` rejeita `security` com
`ERR_INVALID_ARGUMENT`.

## 10. Tela de Segurança

Nova tela na interface, entre "Logs" e "Configurações"
([UI §2](../specs/ui/design.md#2-layout)):

```text
┌──────────────────────────────────────────────────────────────────┐
│ SEGURANÇA                              🟢 protegido · 0 pendentes │
├──────────────────────────────────────────────────────────────────┤
│ ESTADO                                                           │
│  Defense Engine   ● ativo        Detectores    24 ativos         │
│  Lockdown         ○ inativo      Linha de base ● aprendida       │
│  Disjuntores      0 abertos      Quarentena    3 itens           │
├──────────────────────────────────────────────────────────────────┤
│ INCIDENTES (7 dias)                                              │
│  🔴 22:31  MCP example-mcp violou capacidade      contido  [ver] │
│  🟠 18:04  Brute force de 203.0.113.45            contido  [ver] │
│  🟡 14:22  Tráfego anormal na porta 443           resolvido[ver] │
│  🟢 09:10  Resumo diário: 12 eventos informativos          [ver] │
├──────────────────────────────────────────────────────────────────┤
│ QUARENTENA (3)                                    [abrir cofre]  │
│ EXCEÇÕES ATIVAS (2)                               [revisar]      │
│ REGRAS TEMPORÁRIAS (1)  bloqueio de IP expira em 12 min          │
└──────────────────────────────────────────────────────────────────┘
```

"Exceções ativas" e "Regras temporárias" são deliberadamente visíveis na tela
principal. Toda supressão que o usuário criou e toda regra que o Zordon aplicou
ficam à vista e revogáveis — o oposto de configuração que se acumula esquecida.
