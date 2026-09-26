/*
 * Copyright 2026 Willyan Faria
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zordon.core.change;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.Severity;
import zordon.api.trace.Spec;
import zordon.core.agents.AgentProfile;
import zordon.core.agents.AgentRegistry;
import zordon.core.notify.NotificationCenter;
import zordon.core.rag.KnowledgeBase;
import zordon.memory.KnowledgeStore;
import zordon.memory.TaskStore;

/**
 * O preflight de nove passos, escrito e registrado antes de qualquer alteração de
 * projeto (Auto-modificação §6). Ele **não executa nada**: termina esperando a
 * decisão do dono.
 */
@Spec("SPEC-029")
public final class Preflight {

    /** O núcleo de confiança: o que só muda por PR humano (ADR-0024). */
    static final List<String> TRUST_CORE = List.of("zordon-security", "zordon-api/src/main/java/zordon/api/security",
            "permissionengine", "auditlog", "commandvalidator", "gatekeeper", "pathpolicy", "politica de permissao",
            "motor de permissao", "auditoria", "nucleo de confianca");
    /** Palavra no objetivo → agente de engenharia que entra no passo 6. */
    static final Map<String, String> ROUTING = Map.of(
            "spec", "spec", "adr", "spec", "documenta", "documentation", "teste", "testing",
            "tela", "javafx", "java", "java", "revis", "codereview", "arquitet", "architecture");

    public record Step(String id, String title, String finding) {}

    public record Plan(String taskId, String goal, List<Step> steps, boolean touchesTrustCore, long tokenBudget) {}

    static final long DEFAULT_BUDGET = 120_000;

    private static final Logger log = LoggerFactory.getLogger(Preflight.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final TaskStore tasks;
    private final KnowledgeBase knowledge;
    private final AgentRegistry agents;
    private final NotificationCenter notifications;

    public Preflight(TaskStore tasks, KnowledgeBase knowledge, AgentRegistry agents,
            NotificationCenter notifications) {
        this.tasks = tasks;
        this.knowledge = knowledge;
        this.agents = agents;
        this.notifications = notifications;
    }

    public Plan plan(String goal) {
        List<KnowledgeStore.Hit> docs = knowledge.search(goal, 5);
        List<KnowledgeStore.Hit> specs = knowledge.search("SPEC ADR " + goal, 5).stream()
                .filter(hit -> hit.path().contains("/specs/") || hit.path().contains("/adr/")).toList();
        boolean trust = touchesTrustCore(goal, docs);
        List<String> chosen = chooseAgents(goal);
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("s1", "Identificar a tarefa e o escopo", goal));
        steps.add(new Step("s2", "Consultar a documentação (RAG)", docs.isEmpty()
                ? "nada encontrado no índice" + (knowledge.staleFiles() > 0 ? " (e o índice está velho)" : "")
                : docs.stream().map(hit -> hit.heading()).distinct().limit(5)
                        .reduce((a, b) -> a + "; " + b).orElse("")));
        steps.add(new Step("s3", "Localizar SPECs e ADRs relacionados", specs.isEmpty()
                ? "nenhuma SPEC ou ADR casou; pode ser assunto novo"
                : specs.stream().map(hit -> KnowledgeBase.shortPath(hit.path())).distinct()
                        .reduce((a, b) -> a + "; " + b).orElse("")));
        steps.add(new Step("s4", "Criar ou atualizar a SPEC", needsSpec(goal)
                ? "sim: comportamento novo ou mudança de contrato pede SPEC antes do código"
                : "provavelmente não; confirme pela tabela do SDD §4"));
        steps.add(new Step("s5", "Impacto arquitetural e de segurança", trust
                ? "TOCA O NÚCLEO DE CONFIANÇA: só entra por PR humano (ADR-0024); o Zordon propõe, não aplica"
                : "fora do núcleo de confiança; as invariantes de sempre continuam valendo"));
        steps.add(new Step("s6", "Selecionar os agentes", String.join(", ", chosen)));
        steps.add(new Step("s7", "Plano de execução", "SPEC → implementação → testes por critério de aceite →"
                + " documentação → verifyAll"));
        steps.add(new Step("s8", "Orçamento de tokens", DEFAULT_BUDGET + " tokens para esta tarefa"));
        steps.add(new Step("s9", "Comunicar e aguardar", trust
                ? "aguardando decisão do dono; mudança no núcleo de confiança nunca é aplicada sozinha"
                : "aguardando decisão do dono antes de qualquer alteração"));

        List<TaskStore.PlanStep> plan = new ArrayList<>();
        String previous = null;
        for (Step step : steps) {
            plan.add(new TaskStore.PlanStep(step.id(), step.title(), "architecture",
                    previous == null ? List.of() : List.of(previous), "green",
                    "{\"type\":\"human\",\"criterion\":\"o dono decide\"}"));
            previous = step.id();
        }
        String taskId = tasks.createTask("Plano de mudança: " + goal, "change:preflight", plan);
        for (Step step : steps) {
            tasks.stepResult(taskId, step.id(), write(Map.of("finding", step.finding())));
            tasks.stepState(taskId, step.id(), "done", step.finding());
        }
        // O preflight termina esperando: ele planeja, não executa (Auto-modificação §3).
        tasks.taskState(taskId, "waiting_human", "preflight pronto; esperando a decisão do dono");
        notifications.publish(notifications.message(new NotificationCenter.MessageFields(trust ? Severity.HIGH : Severity.INFO, "SYSTEM",
                "Plano de mudança pronto: " + goal,
                "O preflight de nove passos está registrado na tarefa " + taskId + ".",
                trust ? "A mudança toca o núcleo de confiança, que só muda por PR humano."
                        : "Nenhuma alteração foi feita: o preflight só planeja.",
                "preflight de auto-modificação (SPEC-029)",
                "Nada foi alterado no projeto.",
                "tarefa " + taskId, true,
                "Esperando você decidir.",
                List.of("Ver o plano na tela de Tarefas", "Descartar"))));
        log.info("preflight de '{}' registrado em {} (núcleo de confiança: {})", goal, taskId, trust);
        return new Plan(taskId, goal, List.copyOf(steps), trust, DEFAULT_BUDGET);
    }

    static boolean touchesTrustCore(String goal, List<KnowledgeStore.Hit> docs) {
        String plain = plain(goal);
        if (TRUST_CORE.stream().anyMatch(plain::contains)) {
            return true;
        }
        return docs.stream().anyMatch(hit -> plain(hit.path()).contains("zordon-security")
                || plain(hit.path()).contains("/security/"));
    }

    static boolean needsSpec(String goal) {
        String plain = plain(goal);
        return List.of("adicionar", "nova", "novo", "mudar", "trocar", "criar", "remover", "integrar")
                .stream().anyMatch(plain::contains);
    }

    List<String> chooseAgents(String goal) {
        String plain = plain(goal);
        List<String> chosen = new ArrayList<>();
        ROUTING.forEach((word, agent) -> {
            if (plain.contains(word) && !chosen.contains(agent) && agents.find(agent).isPresent()) {
                chosen.add(agent);
            }
        });
        if (chosen.isEmpty()) {
            chosen.add(agents.find("architecture").map(AgentProfile::id).orElse(AgentRegistry.GENERAL));
        }
        if (!chosen.contains("codereview") && agents.find("codereview").isPresent()) {
            chosen.add("codereview");   // revisão não é opcional (Orquestração §6)
        }
        return List.copyOf(chosen);
    }

    private static String plain(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static String write(Map<String, Object> data) {
        try {
            return json.writeValueAsString(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    /** O plano em texto, para a voz e para o chat. */
    public static String summary(Plan plan) {
        StringBuilder out = new StringBuilder("Plano de mudança registrado (" + plan.steps().size() + " passos)");
        if (plan.touchesTrustCore()) {
            out.append(", e ele toca o núcleo de confiança: só entra por PR humano");
        }
        out.append(". Nada foi alterado; está esperando você na tela de Tarefas.");
        return out.toString();
    }

    public static Map<String, Object> wire(Plan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", plan.taskId());
        out.put("goal", plan.goal());
        out.put("touchesTrustCore", plan.touchesTrustCore());
        out.put("tokenBudget", plan.tokenBudget());
        out.put("steps", plan.steps().stream().map(step -> Map.of("id", step.id(), "title", step.title(),
                "finding", step.finding())).toList());
        return out;
    }
}
