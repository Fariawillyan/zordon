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
package zordon.desktop.shell;

import java.util.Map;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * O que as telas de recursos e de operação listam: tarefas, agentes, memória,
 * MCP, automações, ferramentas e os resumos de conhecimento, consumo e sistema.
 */
public final class ResourcesState {

    /** Tarefas recentes, cada uma com as etapas em {@code steps} (SPEC-023). */
    private final ObservableList<Map<String, Object>> tasks = FXCollections.observableArrayList();
    /** Os agentes e os arquivos que não viraram agente (SPEC-022). */
    private final ObservableList<Map<String, Object>> agents = FXCollections.observableArrayList();
    /** O que o Zordon lembra, do mais novo para o mais antigo (SPEC-021). */
    private final ObservableList<Map<String, Object>> memoryFacts = FXCollections.observableArrayList();
    /** Servidores MCP declarados e o estado de cada um (SPEC-020). */
    private final ObservableList<Map<String, Object>> mcpServers = FXCollections.observableArrayList();
    private final ObservableList<Map<String, Object>> automations = FXCollections.observableArrayList();
    private final ObservableList<Map<String, Object>> automationProposals = FXCollections.observableArrayList();
    /** As ferramentas que o modelo pode pedir, com risco e efeitos (SPEC-019). */
    private final ObservableList<Map<String, Object>> skills = FXCollections.observableArrayList();
    /** {@code rag.status} (SPEC-028), {@code usage.summary} (SPEC-029) e {@code system.metrics} (SPEC-024). */
    private final ObjectProperty<Map<String, Object>> knowledge = new SimpleObjectProperty<>(Map.of());
    private final ObjectProperty<Map<String, Object>> usageSummary = new SimpleObjectProperty<>(Map.of());
    private final ObjectProperty<Map<String, Object>> systemMetrics = new SimpleObjectProperty<>(Map.of());

    public ObservableList<Map<String, Object>> tasks() {
        return tasks;
    }

    public ObservableList<Map<String, Object>> agents() {
        return agents;
    }

    public ObservableList<Map<String, Object>> memoryFacts() {
        return memoryFacts;
    }

    public ObservableList<Map<String, Object>> mcpServers() {
        return mcpServers;
    }

    public ObservableList<Map<String, Object>> automations() {
        return automations;
    }

    public ObservableList<Map<String, Object>> automationProposals() {
        return automationProposals;
    }

    public ObservableList<Map<String, Object>> skills() {
        return skills;
    }

    public ObjectProperty<Map<String, Object>> knowledgeProperty() {
        return knowledge;
    }

    public ObjectProperty<Map<String, Object>> usageSummaryProperty() {
        return usageSummary;
    }

    public ObjectProperty<Map<String, Object>> systemMetricsProperty() {
        return systemMetrics;
    }
}
