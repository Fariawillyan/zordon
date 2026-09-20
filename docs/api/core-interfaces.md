---
document: api-core-interfaces
module: api
section: interfaces
version: 1
updatedAt: 2026-09-17
securityLevel: internal
tags: [aiprovider,skill,permission,memory,bridge]
specId: null
---

# API — interfaces do núcleo

Contratos que a implementação precisa honrar. São esboços de assinatura, não
código final — mas as decisões de forma (o que é `record`, o que é `sealed`, o
que retorna `CompletableFuture`) são deliberadas e estão justificadas.

Convenções: `record` para dados imutáveis, `sealed interface` para somas
fechadas, `Optional` só em retorno (nunca em campo ou parâmetro), nada de
`null` atravessando fronteira de módulo.

## 1. Tipos base (`zordon-api`)

```java
public record ZPath(Origin origin, String canonical) {
    public enum Origin { WINDOWS, WSL }
    public String toWindows() { ... }
    public String toWsl() { ... }
    public static ZPath ofWindows(String p) { ... }
    public static ZPath ofWsl(String p) { ... }
}

public enum RiskLevel { GREEN, YELLOW, RED }

public enum Severity { INFO, WARNING, HIGH, CRITICAL }

/** Origem da iniciativa. Determina se a notificação é obrigatória. */
public enum Origin { USER, AGENT, AUTOMATION, DEFENSE }

/**
 * Consequências possíveis de uma ação.
 * NÃO existe DELETE_FS — ver ADR-0015. A operação destrutiva mais
 * forte do sistema é QUARANTINE_FS, que é reversível.
 */
public enum Effect {
    READ_FS, WRITE_FS, QUARANTINE_FS,
    SPAWN_PROCESS, SUSPEND_PROCESS, KILL_PROCESS,
    NETWORK, NETWORK_BLOCK,
    MODIFY_SYSTEM, MODIFY_CREDENTIALS, EXPORT_DATA,

    /** Alterar o repositório do próprio Zordon. Sempre em branch, nunca instalado. */
    MODIFY_SELF,
    /** Alterar um projeto do usuário declarado em paths.workspaces. */
    MODIFY_PROJECT,
    /**
     * Alterar o núcleo de confiança (zordon-security, zordon-defense,
     * zordon-api, ArchUnit, golden, política, CI, CODEOWNERS).
     * SEMPRE RED. Nunca aplicado — apenas proposto via PR. Ver ADR-0024.
     */
    MODIFY_TRUST_KERNEL
}

/** Identificadores opacos e tipados — evita trocar um turnId por um runId. */
public record TurnId(String value) {}
public record RunId(String value) {}
public record CallId(String value) {}
public record SessionId(String value) {}

/**
 * Um evento já carimbado pelo barramento. O `seq` é atribuído na publicação,
 * então o evento não nasce com ele: quem publica informa tipo e payload.
 * Ver SPEC-002 §7 para por que os identificadores de correlação ficam no payload.
 */
public record EventEnvelope(long seq, Instant ts, EventType type, Map<String,Object> payload) {
    public String topic() { return type.topic(); }
}

/** Catálogo fechado de eventos; cada entrada carrega o seu tópico. */
public enum EventType {
    CORE_STARTED(Topic.SYSTEM), SYSTEM_ALERT(Topic.SYSTEM),
    USER_COMMAND(Topic.CHAT), AI_THINKING(Topic.CHAT),
    AI_RESPONSE(Topic.CHAT), AI_ERROR(Topic.CHAT);
    // cresce por marco: um evento entra aqui quando alguém o publica
}
```

`ZPath` em vez de `String` é a defesa contra a classe inteira de bugs descrita em
[Windows↔WSL §R8](../architecture/windows-wsl.md#r8--caminhos-são-dois-mundos). Nenhuma
assinatura pública de Skill aceita caminho como `String`.

## 2. `AiProvider`

O briefing propôs:

```java
AiResponse chat(AiRequest request);
Stream<AiToken> stream(AiRequest request);
```

`Stream<AiToken>` não serve, por quatro motivos concretos:

1. **Não expressa cancelamento.** O usuário interrompe com a voz; é preciso
   abortar a requisição HTTP, não só parar de consumir.
2. **Não expressa erro no meio.** Um `Stream` que lança no meio da iteração
   deixa a conexão pendurada e o consumidor sem contexto parcial.
3. **A saída não é só token.** Um modelo moderno emite blocos de pensamento,
   blocos de texto, chamadas de ferramenta e uso de tokens — tipos diferentes
   no mesmo fluxo.
4. **`Stream` é pull; a rede é push.** Adaptar os dois gera bufferização
   ilimitada, que é justamente o que não queremos num processo residente.

Contrato adotado:

```java
public interface AiProvider {

    ProviderInfo info();

    /** Síncrono. Para roteamento, classificação e tarefas curtas. */
    AiResponse chat(AiRequest request) throws AiException;

    /** Streaming. Retorna handle cancelável; eventos chegam no listener. */
    AiStream stream(AiRequest request, AiStreamListener listener);

    /** Embeddings. Nem todo provider suporta — ver capabilities. */
    default List<float[]> embed(List<String> texts) {
        throw new UnsupportedOperationException();
    }

    /** Contagem de tokens sem gastar geração. Usado para orçamento. */
    long countTokens(AiRequest request);
}

public interface AiStream extends AutoCloseable {
    void cancel();
    CompletableFuture<AiResponse> result();   // completa no fim, com uso e custo
    @Override default void close() { cancel(); }
}

public interface AiStreamListener {
    default void onThinking(String summary) {}
    default void onTextDelta(String delta) {}
    default void onToolUse(ToolCall call) {}
    default void onUsage(TokenUsage usage) {}
    default void onError(AiException e) {}
    default void onDone(StopReason reason) {}
}
```

Modelo de requisição e resposta:

```java
public record AiRequest(
        String model,
        String systemPrompt,
        List<ToolSpec> tools,          // ordenadas por nome: o cache casa por prefixo
        List<AiMessage> messages,
        Effort effort,                 // LOW, MEDIUM, HIGH, XHIGH, MAX
        Thinking thinking,             // ADAPTIVE | OFF
        int maxOutputTokens,
        boolean cacheSystemPrompt,     // marca o bloco estável do prefixo
        Duration timeout) {}

public record AiResponse(
        List<ContentBlock> content,    // texto, pensamento, chamadas de ferramenta
        StopReason stopReason,         // END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, CANCELLED
        TokenUsage usage,
        Money cost,
        String model,
        Duration latency,
        String refusalExplanation,     // presente só em REFUSAL
        boolean usageEstimated) {}     // provider não informou o consumo

public record ProviderInfo(
        String id,                     // "anthropic", "local-llamacpp", ...
        Set<Capability> capabilities,  // STREAMING, TOOLS, VISION, EMBEDDINGS,
                                       // THINKING, PROMPT_CACHE, EFFORT
        List<ModelInfo> models,
        boolean local) {}              // true = nada sai da máquina
```

`effort` pode faltar (`effortIfAny()`), e ausente quer dizer "o padrão do
provider": um modelo que não raciocina recusa `reasoning_effort` com erro 400 em
alguns protocolos. `usageEstimated` existe porque nem todo servidor informa o
consumo, e estimativa exibida como medida é mentira com cara de número
([SPEC-004 §7](../specs/core/SPEC-004-providers-configuraveis.md#7-interfaces)).

Quem escolhe o provider não é o núcleo: é o `ProviderRegistry`, a partir de
`~/.zordon/config.toml`. O núcleo pede "o provider do papel `conversation`" e
recebe um `AiProvider` — ou o motivo, em palavras que o usuário consegue seguir,
de não haver um ([ADR-0026](../adr/ADR-0026-provider-agnostico.md)).

A ordem dos componentes acompanha a ordem de renderização da API — `tools` →
`system` → `messages` — porque é assim que o prefixo cacheável se forma. Duas
diferenças em relação ao esboço original, decididas na
[SPEC-003 §7](../specs/core/SPEC-003-chat-com-streaming.md#7-interfaces): a lista
`cacheHints` virou o booleano `cacheSystemPrompt`, porque enquanto só existe um
bloco estável uma lista de marcadores é estrutura sem uso; e `providerOptions`
não existe, porque um mapa livre de opções é a porta pela qual detalhes de um
provider vazam para o núcleo.

`ProviderInfo.local` não é cosmético: o `PermissionEngine` consulta esse campo
para decidir se conteúdo sensível (de um caminho marcado como confidencial) pode
entrar no prompt daquele provider.

`stopReason` inclui `REFUSAL` porque provedores modernos podem recusar uma
requisição devolvendo HTTP 200 com motivo — tratar isso como sucesso vazio
produz bugs silenciosos. Ver [Core §4](../specs/core/design.md#4-tratamento-de-falhas).

## 3. `ZordonSkill`

O briefing propôs `SkillResult execute(SkillContext)` com `permission()`
estático. Três ajustes:

```java
public interface ZordonSkill {

    String name();                 // "windows.openApplication" — namespace obrigatório
    String description();          // vai para o LLM: escreva para o modelo, não para humano
    JsonSchema inputSchema();      // validação antes de executar, não depois

    /** Risco base da skill, sem olhar os argumentos. */
    RiskLevel baseRisk();

    /**
     * Risco efetivo considerando os argumentos.
     * Ex.: writeFile em ~/notas.md é YELLOW; em C:\Windows\System32 é RED.
     * O padrão devolve o risco base; sobrescreva quando os argumentos importam.
     */
    default RiskAssessment assess(SkillInput input) {
        return RiskAssessment.of(baseRisk());
    }

    /** Execução. Deve respeitar interrupção da thread e o deadline do contexto. */
    SkillResult execute(SkillContext ctx) throws SkillException;
}
```

Por que `assess(input)` e não só `permission()`: a maioria das ações perigosas
não é perigosa pela ferramenta, é perigosa pelo alvo. `files.quarantine` no
diretório de build é rotina; no `.git` é catástrofe. Um risco estático força o usuário a
confirmar tudo (e ele vai começar a clicar "sim" sem ler) ou a confirmar nada.

Contexto e resultado:

```java
public record SkillContext(
        SkillInput input,
        Principal actor,           // qual agente/usuário pediu
        Origin origin,             // USER | AGENT | AUTOMATION | DEFENSE
        TurnId turn,
        Instant deadline,          // execução deve abortar aqui
        WindowsBridge windows,     // pode estar indisponível — checar isAvailable()
        FileAccess files,          // acesso mediado, respeita política de caminhos
        ProcessRunner processes,   // NUNCA ProcessBuilder direto
        EventSink events,          // progresso para a UI
        Logger log) {}

/**
 * Acesso mediado ao sistema de arquivos.
 * Note o que NÃO existe: delete, unlink, rmdir, truncate destrutivo.
 * A única operação destrutiva é quarantine, e ela é reversível.
 */
public interface FileAccess {
    byte[]      read(ZPath path, long maxBytes);
    void        write(ZPath path, byte[] content);
    void        append(ZPath path, byte[] content);
    void        move(ZPath from, ZPath to);
    VaultEntry  quarantine(ZPath path, String reason);   // ← no lugar de delete
    List<ZPath> search(ZPath root, String glob, int limit);
    FileStat    stat(ZPath path);
}

public sealed interface SkillResult {
    record Ok(String summary, JsonNode data, List<Artifact> artifacts) implements SkillResult {}
    record Failed(String message, String kind, boolean retryable) implements SkillResult {}
    record NeedsPermission(ActionDescriptor action) implements SkillResult {}
}
```

`summary` é a frase curta que vai para o log de atividades e para o modelo;
`data` é o JSON estruturado. Separar os dois evita que a Skill formate prosa
para o LLM e evita mandar 4 MB de JSON quando o modelo só precisa de "8
containers, 2 parados".

`NeedsPermission` existe para o caso em que a Skill só descobre o risco real
durante a execução (ex.: o glob casou 400 arquivos em vez de 2). Ela pode parar
e pedir autorização sem ter feito nada.

## 4. `ToolRegistry`

Namespace unificado sobre Skills locais e ferramentas MCP:

```java
public interface ToolRegistry {

    void registerSkill(ZordonSkill skill);
    void registerMcpTools(String server, List<McpTool> tools);
    void unregisterServer(String server);

    Optional<ToolDescriptor> find(String name);   // "skill:files.read", "mcp:docker.logs"
    List<ToolDescriptor> all();

    /** Seleção semântica: o que vai no prompt. Ver ADR-0010. */
    List<ToolDescriptor> select(ToolQuery query);

    ToolResult invoke(String name, JsonNode args, ToolContext ctx);
}

public record ToolQuery(
        String intent,             // texto do pedido do usuário
        AgentId agent,             // limita ao escopo do agente
        Set<String> pinned,        // sempre incluídas
        int limit,                 // teto de ferramentas no prompt
        int tokenBudget) {}

public record ToolDescriptor(
        String name,               // "mcp:docker.listContainers"
        String description,
        JsonSchema inputSchema,
        RiskLevel baseRisk,
        Source source,             // SKILL | MCP
        String owner,              // servidor MCP ou módulo da skill
        Set<String> tags,
        float[] embedding) {}      // calculado uma vez, no registro
```

Prefixos `skill:` e `mcp:` são obrigatórios e visíveis para o modelo. Isso torna
o log de atividades legível e impede colisão quando um MCP server expõe
`readFile` e existe uma Skill `files.read`.

## 5. `PermissionEngine`

O contrato central de segurança:

```java
public interface PermissionEngine {

    /** Classifica uma ação proposta. Puro, determinístico, sem efeito colateral. */
    Decision evaluate(ActionDescriptor action, Principal actor, PolicyContext ctx);

    /** Pede confirmação ao usuário. Bloqueia até decisão, negação ou timeout. */
    CompletableFuture<Decision> requestApproval(ActionDescriptor action, String explanation);

    Policy policy();
    void updatePolicy(Policy policy);
}

public record ActionDescriptor(
        String tool,               // ferramenta resolvida
        Map<String, Object> args,  // argumentos validados (zordon-api não depende de JSON)
        RiskLevel baseRisk,        // piso da ferramenta; o motor só sobe
        Set<Effect> effects,       // ver o enum Effect em §1 — não há DELETE_FS
        List<ZPath> touchedPaths,
        int targets,               // um glob que casa 43 arquivos conta 43
        List<String> command,      // programa e argumentos, quando executa processo
        String humanSummary) {}    // "Mover 43 arquivos de D:\projeto\logs para a quarentena"

public sealed interface Decision {         // cada variante carrega o risco efetivo e o motivo
    record Allow(RiskLevel risk, String reason, boolean session) implements Decision {}
    record Deny(RiskLevel risk, String reason) implements Decision {}
    record AskUser(RiskLevel risk, String reason, Duration ttl, boolean perAction) implements Decision {}
    /** Contenção autônoma pré-autorizada: reversível, com prazo, notificada. */
    record AutoContain(String reason, Duration ttl, String rollbackToken)
            implements Decision {}
}
```

**`humanSummary` é gerado pelo núcleo a partir dos argumentos resolvidos, nunca
pelo modelo.** É o texto que o usuário lê antes de autorizar. Se ele viesse do
modelo, um modelo comprometido poderia descrever "listar arquivos" enquanto pede
exclusão. Ver [Segurança §3](../security/model.md#3-fluxo-de-decisão).

`AutoContain` é o único caminho pelo qual algo executa sem o usuário decidir, e
ele é restrito: só o `DefenseEngine` o obtém, só para o subconjunto de ações
reversíveis e com prazo de
[Defesa §5](../security/defense.md#ações-de-resposta-permitidas), e sempre
acompanhado de notificação em até 2 s.

`effects` existe para permitir políticas por efeito e não só por ferramenta:
"nunca permita `QUARANTINE_FS` fora de `D:\projetos` sem perguntar" é uma regra que
vale para qualquer ferramenta, presente ou futura — inclusive uma de um MCP
server que ainda não existe.

## 6. `WindowsBridge`

Interface no núcleo, implementação no `zordon-host`, alcançada por ZWP inverso.

```java
public interface WindowsBridge {

    boolean isAvailable();

    CompletableFuture<ProcessHandle2> openApplication(AppTarget target, List<String> args);
    CompletableFuture<Void>           closeApplication(ProcessSelector sel, boolean force);
    CompletableFuture<List<WinProcess>> listProcesses(ProcessFilter filter);

    CompletableFuture<String>  readClipboard();
    CompletableFuture<Void>    writeClipboard(String text);

    CompletableFuture<ImageRef> screenshot(ScreenRegion region);
    CompletableFuture<Void>     notify(Notification n);
    CompletableFuture<Void>     focusWindow(WindowSelector sel);
}

/** Alvo nomeado e resolvido, nunca uma linha de comando crua. */
public sealed interface AppTarget {
    record Known(String id) implements AppTarget {}       // "intellij", "chrome"
    record Path(ZPath executable) implements AppTarget {}
    record Uri(String uri) implements AppTarget {}        // ms-settings:, http(s)
}
```

Duas regras que definem o módulo:

1. **Nenhuma string de PowerShell atravessa esta interface.** `openApplication`
   recebe um alvo tipado. A tradução para uma chamada Win32 acontece dentro do
   host, com argumentos passados como array, nunca por concatenação.
2. **`AppTarget.Known` resolve por um catálogo**
   (`~/.zordon/apps.toml`) que mapeia apelidos para executáveis. "Zordon, abra o
   IntelliJ" não faz o modelo adivinhar um caminho: ele escolhe um identificador
   de uma lista conhecida. Um alvo desconhecido é `Path`, que é YELLOW.

## 7. `Agent` e `AgentOrchestrator`

```java
public interface Agent {
    AgentId id();
    String displayName();
    String systemPrompt();
    ToolScope toolScope();        // quais ferramentas este agente pode ver
    RiskLevel permissionCeiling();// teto: o agente nunca pede acima disso
    ModelPolicy modelPolicy();    // modelo preferido e fallback
    Budget defaultBudget();
}

public interface AgentOrchestrator {
    AgentRun start(AgentId agent, Task task, Budget budget);
    void cancel(RunId id);
    List<AgentRun> active();
    Optional<AgentRun> get(RunId id);
}

public record Budget(
        int maxSteps,             // voltas do laço de ferramentas
        long maxTokens,
        Duration wallClock,
        Money maxCost,
        int maxToolCalls) {}
```

`permissionCeiling` é uma trava dura: um `ResearchAgent` com teto GREEN não
consegue nem *pedir* para escrever arquivo. Isso limita o estrago de uma
injeção de prompt em conteúdo pesquisado na web, que é exatamente o vetor mais
provável para esse agente.

## 8. `MemoryStore`

```java
public interface MemoryStore {

    // curto prazo — RAM, janela da conversa atual
    WorkingMemory working(SessionId session);

    // conversa — durável
    void appendMessage(SessionId session, StoredMessage msg);
    List<StoredMessage> history(SessionId session, Instant before, int limit);

    // longo prazo — fatos destilados
    FactId remember(Fact fact);
    List<Fact> recall(RecallQuery query);
    void forget(FactId id);

    // recuperação híbrida: BM25 (FTS5) + vetorial, fundidos por RRF
    List<MemoryHit> search(String query, Set<MemoryKind> kinds, int limit);
}

public record Fact(
        MemoryKind kind,          // PREFERENCE, PROJECT, ENTITY, EVENT, PROCEDURE
        String subject,           // "projeto Aurora"
        String content,
        double confidence,
        Instant observedAt,
        Instant expiresAt,        // null = permanente
        String provenance) {}     // turnId de origem — sempre rastreável
```

`provenance` é obrigatório. Toda lembrança precisa poder responder "onde foi que
eu disse isso?". Sem isso, memória errada vira mito impossível de corrigir.
Ver [Memória](../specs/memory/design.md).

## 9. `EventBus`

```java
public interface EventBus {
    void publish(ZordonEvent event);              // nunca bloqueia
    Subscription subscribe(Set<String> topics, EventListener listener, QueuePolicy policy);
    List<ZordonEvent> replay(long fromSeq, int limit);
}

public record QueuePolicy(int capacity, Overflow overflow) {
    public enum Overflow { DROP_OLDEST, COALESCE, REJECT_PUBLISH }
}
```

`REJECT_PUBLISH` existe para o tópico `permission`: se a fila de permissão
encher, algo está muito errado e é melhor falhar alto do que descartar um pedido
de autorização em silêncio. Não existe opção `BLOCK_PRODUCER`.

## 10. `AutomationEngine`

```java
public interface AutomationEngine {
    AutomationId create(AutomationSpec spec);
    void pause(AutomationId id);
    void delete(AutomationId id);
    List<Automation> list();
}

public sealed interface Trigger {
    record Schedule(String cron, ZoneId zone, CatchUp catchUp) implements Trigger {}
    record Interval(Duration every) implements Trigger {}
    record OnEvent(String eventType, JsonNode filter) implements Trigger {}
    record OnCondition(ConditionSpec cond, Duration checkEvery,
                       Duration sustainedFor) implements Trigger {}
}

public enum CatchUp { SKIP, ONCE, ALL }
```

`sustainedFor` em `OnCondition` é o que impede a automação "me avise se a CPU
passar de 90%" de disparar quarenta vezes durante um build. A condição precisa
ser verdadeira continuamente pelo período antes de disparar, e há histerese na
volta. Ver [Automação §4](../specs/automation/design.md#4-histerese-e-deduplicação).

`CatchUp` responde ao risco [R2](../architecture/windows-wsl.md#r2--o-wsl-é-derrubado-por-fora):
o que fazer com disparos perdidos enquanto o WSL estava desligado.

## 11. O que propositalmente não existe

| Não existe | Por quê |
|---|---|
| `ShellSkill` / `exec(String)` | É a negação do modelo de segurança inteiro |
| Método ZWP `system.exec` | Idem, pela porta dos fundos |
| `PermissionEngine.bypass()` | Não há caso de uso legítimo; haveria abuso |
| `Agent.setPermissionCeiling()` | Teto é configuração, não estado de execução |
| Acesso direto a `ProcessBuilder` fora de `zordon-security` | Verificado por ArchUnit |
| `MemoryStore.clear()` | Esquecer é por fato, auditável; apagar tudo é operação manual |
| `FileAccess.delete()` / `unlink` / `rmdir` | [ADR-0015](../adr/ADR-0015-exclusao-impossivel-por-construcao.md). `Effect.DELETE_FS` também não existe |
| `SecurityPolicy.set()` / método ZWP `security.policy.set` | A política só muda pelo usuário no sistema de arquivos, com reinício |
| Escrita em `~/.local/share/zordon/**` ou `%LOCALAPPDATA%\...\Zordon\**` | O Zordon não se instala ([ADR-0024](../adr/ADR-0024-auto-modificacao-e-nucleo-de-confianca.md)) |
| `ChangeExecutor.execute(ChangePlan)` sem aprovação | Só aceita `ApprovedPlan`, que exige `userMessageId` |
| Reinício do próprio serviço com código novo | Instalar é ação humana |
| `PermissionEngine.grant()` | Capacidades vêm da política, nunca de quem as usaria |
| `CircuitBreaker.close()` acessível a agente | Só o usuário fecha ([ADR-0018](../adr/ADR-0018-circuit-breaker-e-lockdown.md)) |
| `Lockdown.exit()` sem `OPERATOR` | Um Zordon comprometido sairia imediatamente |
| `NotificationCenter.suppress()` para classes críticas | `policy-tamper`, `audit-chain`, `self`, `capability-violation` sempre interrompem |

A ausência dessas APIs é um requisito arquitetural, não um esquecimento. Se
alguém precisar de uma delas no futuro, o caminho é um ADR que explique por quê.

As quatro primeiras linhas desta tabela são verificadas por ArchUnit e reprovam o
build ([Componentes §4](../architecture/components.md#4-regras-de-dependência-verificadas-no-build)).
As contratuais de defesa e notificação estão em
[Defesa](../security/defense.md) e [Comunicação](../security/communication.md).
