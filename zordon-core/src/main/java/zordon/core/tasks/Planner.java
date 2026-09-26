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
package zordon.core.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiException;
import zordon.ai.ModelRole;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.trace.Spec;
import zordon.core.agents.AgentProfile;
import zordon.core.agents.AgentRegistry;
import zordon.core.chat.RoleModels;
import zordon.memory.TaskStore;

/**
 * O Planner (Planner §2): transforma o pedido num plano validado. Não executa nada e
 * não escolhe ferramenta; um plano inválido é refeito uma vez, com o motivo.
 */
@Spec("SPEC-023")
public final class Planner {

    static final int MAX_STEPS = 10;

    /** Plano recusado duas vezes, ou modelo indisponível. */
    public static final class PlanException extends Exception {
        PlanException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Planner.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final ProviderRegistry providers;
    private final AgentRegistry agents;
    /** As ferramentas GREEN e visíveis: as únicas que um critério {@code tool} pode usar. */
    private final Supplier<Set<String>> checkTools;

    public Planner(ProviderRegistry providers, AgentRegistry agents, Supplier<Set<String>> checkTools) {
        this.providers = providers;
        this.agents = agents;
        this.checkTools = checkTools;
    }

    public List<TaskStore.PlanStep> plan(String goal) throws PlanException {
        String problem = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String answer = ask(goal, problem);
            try {
                return validate(answer);
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
                log.info("plano recusado (tentativa {}): {}", attempt, problem);
            }
        }
        throw new PlanException("o plano veio inválido duas vezes: " + problem);
    }

    private String ask(String goal, String problem) throws PlanException {
        ProviderRegistry.Selection selection = RoleModels.first(providers, ModelRole.AGENT_HEAVY, ModelRole.CONVERSATION)
                .orElseThrow(() -> new PlanException("nenhum modelo disponível para planejar"));
        StringBuilder request = new StringBuilder("[Pedido do usuário]\n").append(goal);
        if (problem != null) {
            request.append("\n\n[O plano anterior foi recusado]\n").append(problem).append("\nCorrija e devolva o JSON.");
        }
        try {
            return RoleModels.ask(selection, system(), request.toString(), 2_048).text();
        } catch (AiException e) {
            throw new PlanException("o modelo não planejou: " + e.getMessage());
        }
    }

    private String system() {
        StringBuilder text = new StringBuilder("""
                Você é o Planner do Zordon. Transforme o pedido num plano de 1 a 10 etapas. Você não executa nada.
                Responda SOMENTE com JSON, sem texto antes ou depois:
                {"steps": [{"id": "s1", "title": "título curto, é o que a voz fala",
                  "agent": "id do agente", "dependsOn": [], "risk": "green|yellow|red",
                  "doneWhen": {"type": "tool", "tool": "nome", "args": {}, "expect": "texto esperado"}}]}
                Tipos de doneWhen:
                - "tool": uma ferramenta de leitura confere o estado; "expect" é um trecho que precisa aparecer.
                - "judgement": {"type": "judgement", "criterion": "o que precisa ser verdade"}; outro agente julga
                  pelas evidências. Só para etapas sem efeito (risk green).
                - "human": {"type": "human", "criterion": "..."}; o usuário confirma na tela.
                Prefira "tool" quando houver como conferir. risk é a estimativa do efeito da etapa.
                Agentes:
                """);
        for (AgentProfile agent : agents.list()) {
            text.append("- ").append(agent.id()).append(": ").append(agent.description()).append('\n');
        }
        text.append("Ferramentas que podem conferir (doneWhen tool): ")
                .append(String.join(", ", checkTools.get().stream().sorted().toList())).append('.');
        return text.toString();
    }

    /** As regras do plano (SPEC-023 §3). */
    List<TaskStore.PlanStep> validate(String answer) {
        JsonNode steps = steps(answer);
        List<TaskStore.PlanStep> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode step : steps) {
            out.add(step(step, ids));
        }
        checkDependencies(out, ids);
        if (topological(out) == null) {
            throw new IllegalArgumentException("o plano tem um ciclo de dependências");
        }
        return List.copyOf(out);
    }

    /** O array {@code steps} de dentro da resposta do modelo, já conferido. */
    private JsonNode steps(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("a resposta não tem um objeto JSON");
        }
        JsonNode root;
        try {
            root = json.readTree(answer.substring(start, end + 1));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("JSON inválido: " + e.getMessage());
        }
        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            throw new IllegalArgumentException("o plano não tem etapas");
        }
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("o plano tem " + steps.size() + " etapas; o máximo é " + MAX_STEPS);
        }
        return steps;
    }

    /** Uma etapa conferida. {@code ids} acumula, para recusar repetição. */
    private TaskStore.PlanStep step(JsonNode step, Set<String> ids) {
        String id = step.path("id").asText("");
        if (!id.matches("[a-zA-Z0-9_-]{1,20}") || !ids.add(id)) {
            throw new IllegalArgumentException("id de etapa inválido ou repetido: '" + id + "'");
        }
        String title = step.path("title").asText("").strip();
        if (title.isEmpty() || title.length() > 120) {
            throw new IllegalArgumentException("a etapa " + id + " precisa de um título de até 120 caracteres");
        }
        String agent = agents.find(step.path("agent").asText("")).map(AgentProfile::id)
                .orElse(AgentRegistry.GENERAL);
        List<String> deps = new ArrayList<>();
        step.path("dependsOn").forEach(dep -> deps.add(dep.asText()));
        String risk = step.path("risk").asText("yellow").toLowerCase(Locale.ROOT);
        if (!Set.of("green", "yellow", "red").contains(risk)) {
            risk = "yellow";
        }
        return new TaskStore.PlanStep(id, title, agent, List.copyOf(deps), risk,
                doneWhen(id, step.path("doneWhen"), risk));
    }

    /** Nenhuma etapa depende do que não existe, nem de si mesma. */
    private static void checkDependencies(List<TaskStore.PlanStep> out, Set<String> ids) {
        for (TaskStore.PlanStep step : out) {
            for (String dep : step.dependsOn()) {
                if (!ids.contains(dep) || dep.equals(step.id())) {
                    throw new IllegalArgumentException("a etapa " + step.id() + " depende de '" + dep
                            + "', que não existe no plano");
                }
            }
        }
    }

    private String doneWhen(String id, JsonNode node, String risk) {
        ObjectNode out = json.createObjectNode();
        String type = node.path("type").asText("human").toLowerCase(Locale.ROOT);
        String criterion = node.path("criterion").asText("");
        switch (type) {
            case "tool" -> {
                String tool = node.path("tool").asText("");
                String expect = node.path("expect").asText("");
                if (!checkTools.get().contains(tool) || expect.isBlank()) {
                    throw new IllegalArgumentException("a etapa " + id + " usa a ferramenta '" + tool
                            + "' para conferir, que não é de leitura ou não existe, ou não diz o que esperar");
                }
                out.put("type", "tool").put("tool", tool).put("expect", expect);
                out.set("args", node.path("args").isObject() ? node.path("args") : json.createObjectNode());
            }
            case "judgement" -> {
                // Julgamento nunca é o único critério de algo com efeito (Avaliação §2).
                out.put("type", "green".equals(risk) ? "judgement" : "human").put("criterion", criterion);
            }
            default -> out.put("type", "human").put("criterion", criterion.isBlank() ? "confirmar o resultado" : criterion);
        }
        return out.toString();
    }

    /** Ordem de execução; {@code null} se houver ciclo. */
    static List<TaskStore.PlanStep> topological(List<TaskStore.PlanStep> steps) {
        Map<String, Integer> pending = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        steps.forEach(step -> {
            pending.put(step.id(), step.dependsOn().size());
            step.dependsOn().forEach(dep -> dependents.computeIfAbsent(dep, key -> new ArrayList<>()).add(step.id()));
        });
        ArrayDeque<String> ready = new ArrayDeque<>();
        steps.stream().filter(step -> step.dependsOn().isEmpty()).forEach(step -> ready.add(step.id()));
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String next = ready.poll();
            order.add(next);
            for (String dependent : dependents.getOrDefault(next, List.of())) {
                if (pending.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != steps.size()) {
            return null;
        }
        Map<String, TaskStore.PlanStep> byId = new HashMap<>();
        steps.forEach(step -> byId.put(step.id(), step));
        return order.stream().map(byId::get).toList();
    }
}
