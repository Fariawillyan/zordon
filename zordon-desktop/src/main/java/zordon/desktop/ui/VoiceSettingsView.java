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
package zordon.desktop.ui;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.VoiceDevice;
import zordon.desktop.shell.VoicePresentation;
import zordon.desktop.shell.VoiceStatus;

/**
 * Ajustes da voz (SPEC-010 §3): modo, microfone, dispositivo, teste do
 * microfone, motor e transcrições — o que saiu da tela de Voz, que agora é só o
 * console.
 *
 * <p>Nada aqui muda de estado sozinho. Escolher um modo pede ao núcleo e a tela
 * redesenha com o que ele responder — é assim que "desligado" só aparece
 * confirmado.
 */
@Spec("SPEC-010")
final class VoiceSettingsView extends ScrollPane {

    static final List<String> MODES = List.of("off", "wake", "push", "open");

    private final VBox content = new VBox(16);
    private final VBox modeArea = new VBox(8);
    private final VBox details = new VBox(12);
    private final DesktopState state;
    private final ShellActions actions;
    private ProgressBar liveMeter;
    private Label liveLevel;

    VoiceSettingsView(DesktopState state, ShellActions actions) {
        this.state = state;
        this.actions = actions;
        getStyleClass().addAll("page", "settings-page");
        setFitToWidth(true);
        content.setPadding(new Insets(16, 16, 24, 16));
        content.setFillWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        Label title = new Label("Ajustes da voz");
        title.getStyleClass().add("page-title");
        content.getChildren().setAll(title, modeArea, details, security(state, actions), quarantine(state, actions),
                automations(state, actions), tasks(state, actions), memory(state, actions), agents(state, actions), mcp(state, actions),
                technicalMode(state));
        setContent(content);
        state.voiceProperty().addListener((observable, before, now) -> {
            render();
        });
        state.voiceErrorProperty().addListener((observable, before, now) -> render());
        state.voiceLevelProperty().addListener((observable, before, now) -> showLevel(now));
        state.voiceDevices().addListener((ListChangeListener<VoiceDevice>) change -> render());
        state.transcriptions().addListener((ListChangeListener<String>) change -> render());
        render();
    }

    private void render() {
        modeArea.getChildren().clear();
        details.getChildren().clear();
        if (!state.voiceErrorProperty().get().isEmpty()) {
            modeArea.getChildren().add(warning("Falha ao falar com o núcleo sobre a voz: "
                    + state.voiceErrorProperty().get()));
        }
        VoiceStatus voice = state.voiceProperty().get();
        VoicePresentation.Screen screen = voice == null ? null
                : VoicePresentation.screen(voice, ZoneId.systemDefault());
        modeArea.getChildren().addAll(mode(voice, screen), microphoneTest(voice));
        if (voice == null) {
            modeArea.getChildren().add(muted("Estado da voz desconhecido enquanto o núcleo não responde."));
            return;
        }
        if (!screen.reason().isEmpty()) modeArea.getChildren().add(warning(screen.reason()));
        details.getChildren().addAll(
                Inspector.rows("Pedido", screen.desired(), "Em vigor", screen.effective()),
                microphone(voice, screen),
                HomeView.section("Motor de voz", new VBox(8,
                        Inspector.rows("Estado", screen.engine()),
                        muted("VAD, palavra de ativação, transcrição e fala. Chega com o sidecar zordon-voice."))),
                transcriptions());
    }

    /** O kill switch (SPEC-015 CA-6): pausar é um clique; retomar também, mas só daqui ou da bandeja. */
    private static javafx.scene.Node security(DesktopState state, ShellActions actions) {
        Label status = new Label();
        status.setWrapText(true);
        javafx.scene.control.Button toggle = new javafx.scene.control.Button();
        toggle.setId("lockdown-toggle");
        Runnable render = () -> {
            boolean paused = state.lockdownProperty().get();
            String reason = state.lockdownReasonProperty().get();
            status.setText(paused
                    ? "Pausado: só leitura. Nada que escreve, executa ou envia roda até você retomar."
                            + (reason.isBlank() ? "" : " Motivo: " + reason + ".")
                    : "Ativo. \"Pausar Zordon\" põe tudo em só leitura na hora; a voz continua respondendo.");
            toggle.setText(paused ? "Retomar Zordon" : "Pausar Zordon");
            toggle.getStyleClass().setAll("button", paused ? "button-primary" : "button-danger");
            toggle.setOnAction(event -> {
                if (state.lockdownProperty().get()) {
                    actions.resumeZordon();
                } else {
                    actions.pauseZordon();
                }
            });
        };
        state.lockdownProperty().addListener((observable, before, now) -> render.run());
        state.lockdownReasonProperty().addListener((observable, before, now) -> render.run());
        render.run();
        VBox list = new VBox(8);
        list.setId("finding-list");
        javafx.scene.control.Button refresh = new javafx.scene.control.Button("Atualizar");
        refresh.setId("finding-refresh");
        refresh.setOnAction(event -> actions.loadFindings());
        Runnable findings = () -> {
            list.getChildren().clear();
            if (state.findings().isEmpty()) {
                list.getChildren().add(muted("Nada estranho até agora."));
            }
            for (Map<String, Object> finding : state.findings()) {
                String id = String.valueOf(finding.get("findingId"));
                Label line = new Label(String.valueOf(finding.get("severity")).toUpperCase(java.util.Locale.ROOT)
                        + " · " + finding.get("title") + "\n" + finding.get("rationale"));
                line.setWrapText(true);
                javafx.scene.control.Button seen = new javafx.scene.control.Button("Li");
                seen.setId("finding-ack-" + id);
                seen.setOnAction(event -> actions.acknowledgeFinding(id));
                javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, line, seen);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                javafx.scene.layout.HBox.setHgrow(line, javafx.scene.layout.Priority.ALWAYS);
                list.getChildren().add(row);
            }
        };
        state.findings().addListener((ListChangeListener<Map<String, Object>>) change -> findings.run());
        findings.run();
        VBox open = new VBox(8);
        open.setId("breaker-list");
        Runnable breakers = () -> {
            open.getChildren().clear();
            for (Map<String, Object> breaker : state.breakers()) {
                String subject = String.valueOf(breaker.get("subject"));
                Label line = new Label(subject + " · " + breakerLabel(String.valueOf(breaker.get("state"))) + " · "
                        + breaker.get("reason"));
                line.setWrapText(true);
                javafx.scene.control.Button supervised = new javafx.scene.control.Button("Liberar supervisionado");
                supervised.setId("breaker-supervised-" + subject);
                supervised.setOnAction(event -> actions.releaseBreaker(subject, "supervised"));
                javafx.scene.control.Button closed = new javafx.scene.control.Button("Liberar");
                closed.setId("breaker-closed-" + subject);
                closed.setOnAction(event -> actions.releaseBreaker(subject, "closed"));
                javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, line, supervised, closed);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                javafx.scene.layout.HBox.setHgrow(line, javafx.scene.layout.Priority.ALWAYS);
                open.getChildren().add(row);
            }
        };
        state.breakers().addListener((ListChangeListener<Map<String, Object>>) change -> breakers.run());
        breakers.run();
        Label hint = muted("O que a defesa achou: cada item diz por que foi considerado suspeito. Disjuntor aberto"
                + " só fecha por decisão sua.");
        return HomeView.section("Segurança", new VBox(10, status, toggle, hint, open, list, refresh));
    }

    /** A quarentena (SPEC-017): o que foi "apagado" continua aqui até ser restaurado. */
    private static javafx.scene.Node quarantine(DesktopState state, ShellActions actions) {
        VBox list = new VBox(8);
        list.setId("quarantine-list");
        Label notice = new Label();
        notice.setWrapText(true);
        notice.textProperty().bind(state.quarantineNoticeProperty());
        javafx.scene.control.Button refresh = new javafx.scene.control.Button("Atualizar");
        refresh.setId("quarantine-refresh");
        refresh.setOnAction(event -> actions.loadQuarantine());
        Runnable render = () -> {
            list.getChildren().clear();
            if (state.quarantine().isEmpty()) {
                list.getChildren().add(muted("Nada na quarentena."));
            }
            for (Map<String, Object> item : state.quarantine()) {
                boolean restored = Boolean.TRUE.equals(item.get("restored"));
                Label line = new Label(item.get("root") + " · " + item.get("files") + " arquivos · "
                        + String.valueOf(item.get("ts")).replace('T', ' ').replaceAll("\\..*", "")
                        + (restored ? " · restaurado" : ""));
                line.setWrapText(true);
                javafx.scene.control.Button restore = new javafx.scene.control.Button("Restaurar");
                restore.setDisable(restored);
                String id = String.valueOf(item.get("vaultId"));
                restore.setId("restore-" + id);
                restore.setOnAction(event -> actions.restoreQuarantine(id));
                javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, line, restore);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                javafx.scene.layout.HBox.setHgrow(line, javafx.scene.layout.Priority.ALWAYS);
                list.getChildren().add(row);
            }
        };
        state.quarantine().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        Label hint = muted("O Zordon não apaga: o que você mandou para a quarentena fica guardado e volta com um clique.");
        return HomeView.section("Quarentena", new VBox(10, hint, list, notice, refresh));
    }

    /** A memória (SPEC-021): o que o Zordon sabe, de onde veio, e o botão de esquecer. */
    private static javafx.scene.Node memory(DesktopState state, ShellActions actions) {
        VBox list = new VBox(8);
        list.setId("memory-list");
        javafx.scene.control.Button refresh = new javafx.scene.control.Button("Atualizar");
        refresh.setId("memory-refresh");
        refresh.setOnAction(event -> actions.loadMemory());
        Runnable render = () -> {
            list.getChildren().clear();
            if (state.memoryFacts().isEmpty()) {
                list.getChildren().add(muted("Nada lembrado ainda. Diga \"Zordon, lembre que…\"."));
            }
            for (Map<String, Object> fact : state.memoryFacts()) {
                String id = String.valueOf(fact.get("id"));
                Label line = new Label(kindLabel(String.valueOf(fact.get("kind"))) + " · " + fact.get("subject") + ": "
                        + fact.get("content") + " · " + String.valueOf(fact.get("observedAt")).replaceAll("T.*", ""));
                line.setWrapText(true);
                javafx.scene.control.Button forget = new javafx.scene.control.Button("Esquecer");
                forget.setId("forget-" + id);
                forget.setOnAction(event -> actions.forgetFact(id));
                javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, line, forget);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                javafx.scene.layout.HBox.setHgrow(line, javafx.scene.layout.Priority.ALWAYS);
                list.getChildren().add(row);
            }
        };
        state.memoryFacts().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        Label hint = muted("O Zordon lembra o que você pediu, os projetos em que trabalhou e o que foi decidido nas"
                + " conversas. Esquecer apaga de verdade.");
        return HomeView.section("Memória", new VBox(10, hint, list, refresh));
    }

    /** As tarefas (SPEC-023): etapas, o que espera você e o que foi interrompido. */
    private static javafx.scene.Node tasks(DesktopState state, ShellActions actions) {
        VBox list = new VBox(10);
        list.setId("task-list");
        javafx.scene.control.Button refresh = new javafx.scene.control.Button("Atualizar");
        refresh.setId("task-refresh");
        refresh.setOnAction(event -> actions.loadTasks());
        Runnable render = () -> {
            list.getChildren().clear();
            if (state.tasks().isEmpty()) {
                list.getChildren().add(muted("Nenhuma tarefa. Diga \"Zordon, planeje e faça: …\"."));
            }
            for (Map<String, Object> task : state.tasks()) {
                String taskId = String.valueOf(task.get("taskId"));
                VBox box = new VBox(4);
                Label goal = new Label(task.get("goal") + " · " + taskLabel(String.valueOf(task.get("state")))
                        + (task.get("reason") instanceof String reason ? " · " + reason : ""));
                goal.setWrapText(true);
                box.getChildren().add(goal);
                if (task.get("stepList") instanceof List<?> steps) {
                    for (Object item : steps) {
                        if (!(item instanceof Map<?, ?> step)) {
                            continue;
                        }
                        String stepId = String.valueOf(step.get("id"));
                        Label line = new Label("  " + taskLabel(String.valueOf(step.get("state"))) + " · "
                                + step.get("title"));
                        line.setWrapText(true);
                        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8, line);
                        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                        javafx.scene.layout.HBox.setHgrow(line, javafx.scene.layout.Priority.ALWAYS);
                        if ("waiting_human".equals(step.get("state"))) {
                            javafx.scene.control.Button pass = new javafx.scene.control.Button("Confirmar");
                            pass.setId("confirm-" + taskId + "-" + stepId);
                            pass.setOnAction(event -> actions.confirmStep(taskId, stepId, true));
                            javafx.scene.control.Button fail = new javafx.scene.control.Button("Reprovar");
                            fail.setId("reject-" + taskId + "-" + stepId);
                            fail.setOnAction(event -> actions.confirmStep(taskId, stepId, false));
                            row.getChildren().addAll(pass, fail);
                        }
                        box.getChildren().add(row);
                    }
                }
                if ("blocked".equals(task.get("state"))) {
                    javafx.scene.control.Button resume = new javafx.scene.control.Button("Continuar");
                    resume.setId("resume-" + taskId);
                    resume.setOnAction(event -> actions.resumeTask(taskId));
                    box.getChildren().add(resume);
                }
                list.getChildren().add(box);
            }
        };
        state.tasks().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        Label hint = muted("Uma etapa só conta como concluída depois de conferida. Tarefa interrompida espera você"
                + " decidir: nada é repetido sozinho.");
        return HomeView.section("Tarefas", new VBox(10, hint, list, refresh));
    }

    static String taskLabel(String state) {
        return switch (state) {
            case "planned" -> "planejada";
            case "running" -> "em andamento";
            case "verifying" -> "conferindo";
            case "done" -> "concluída";
            case "failed" -> "falhou";
            case "waiting_human" -> "espera você";
            case "blocked" -> "bloqueada";
            case "cancelled" -> "cancelada";
            default -> state;
        };
    }

    static javafx.scene.Node automations(DesktopState state, ShellActions actions) {
        VBox list = new VBox(12);
        list.setId("automation-list");
        javafx.scene.control.Button refresh = new javafx.scene.control.Button("Atualizar automações");
        refresh.setId("automation-refresh");
        refresh.setOnAction(event -> actions.loadAutomations());
        Runnable render = () -> {
            list.getChildren().clear();
            if (state.automations().isEmpty() && state.automationProposals().isEmpty()) {
                list.getChildren().add(muted("Nenhuma automação. Peça ao Zordon para propor uma regra e aprove aqui."));
            }
            for (Map<String, Object> proposal : state.automationProposals()) {
                String id = String.valueOf(proposal.get("proposalId"));
                Label summary = new Label(String.valueOf(proposal.get("summary")));
                summary.setWrapText(true);
                javafx.scene.control.Button approve = new javafx.scene.control.Button("Aprovar");
                approve.setId("automation-approve-" + id);
                approve.setOnAction(event -> actions.approveAutomation(id));
                javafx.scene.control.Button reject = new javafx.scene.control.Button("Recusar");
                reject.setId("automation-reject-" + id);
                reject.setOnAction(event -> actions.rejectAutomation(id));
                list.getChildren().add(new VBox(8, summary, new javafx.scene.layout.HBox(8, approve, reject)));
            }
            for (Map<String, Object> automation : state.automations()) {
                String id = String.valueOf(automation.get("id"));
                boolean enabled = Boolean.TRUE.equals(automation.get("enabled"));
                Label summary = new Label(automation.get("name") + " · " + (enabled ? "ativa" : "desativada")
                        + "\n" + automation.getOrDefault("trigger", "")
                        + (automation.containsKey("lastFiredAt") ? "\nÚltimo disparo: " + automation.get("lastFiredAt") : "")
                        + (automation.containsKey("reason") ? "\n" + automation.get("reason") : ""));
                summary.setWrapText(true);
                VBox row = new VBox(8, summary);
                if (automation.containsKey("trigger")) {
                    javafx.scene.control.Button toggle = new javafx.scene.control.Button(enabled ? "Desativar" : "Ativar");
                    toggle.setId("automation-toggle-" + id);
                    toggle.setOnAction(event -> actions.enableAutomation(id, !enabled));
                    javafx.scene.control.Button run = new javafx.scene.control.Button("Executar agora");
                    run.setId("automation-run-" + id);
                    run.setDisable(!enabled || Boolean.TRUE.equals(automation.get("running")));
                    run.setOnAction(event -> actions.runAutomation(id));
                    row.getChildren().add(new javafx.scene.layout.HBox(8, toggle, run));
                }
                list.getChildren().add(row);
            }
        };
        state.automations().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        state.automationProposals().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        VBox controls = new VBox(10, list, refresh);
        controls.disableProperty().bind(state.connectionProperty().isNotEqualTo(zordon.zwp.CoreConnection.State.ONLINE));
        return HomeView.section("Automações", controls);
    }

    /** Os agentes (SPEC-022): criar um é criar um arquivo em ~/.zordon/agents/. */
    private static javafx.scene.Node agents(DesktopState state, ShellActions actions) {
        VBox list = new VBox(6);
        list.setId("agent-list");
        javafx.scene.control.Button refresh = new javafx.scene.control.Button("Atualizar");
        refresh.setId("agent-refresh");
        refresh.setOnAction(event -> actions.loadAgents());
        Runnable render = () -> {
            list.getChildren().clear();
            for (Map<String, Object> agent : state.agents()) {
                Label line = new Label(agent.containsKey("reason")
                        ? "Arquivo ignorado: " + agent.get("file") + " · " + agent.get("reason")
                        : agent.get("id") + " · teto " + agent.get("ceiling") + " · " + agent.get("description")
                                + ("builtin".equals(agent.get("source")) ? "" : " · seu"));
                line.setWrapText(true);
                list.getChildren().add(line);
            }
        };
        state.agents().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        Label hint = muted("Diga \"pergunta pro developer: …\" para escolher um agente. Para criar um, ponha um"
                + " arquivo .toml em ~/.zordon/agents/.");
        return HomeView.section("Agentes", new VBox(10, hint, list, refresh));
    }

    static String breakerLabel(String state) {
        return switch (state) {
            case "open" -> "aberto (bloqueado)";
            case "half_open" -> "em prova (cada ação confirmada)";
            default -> state;
        };
    }

    static String kindLabel(String kind) {
        return switch (kind) {
            case "PREFERENCE" -> "preferência";
            case "PROJECT" -> "projeto";
            case "EVENT" -> "evento";
            case "PROCEDURE" -> "procedimento";
            default -> "fato";
        };
    }

    /** Os servidores MCP (SPEC-020): estado, ferramentas e a aprovação de uma superfície nova. */
    private static javafx.scene.Node mcp(DesktopState state, ShellActions actions) {
        VBox list = new VBox(8);
        list.setId("mcp-list");
        javafx.scene.control.Button refresh = new javafx.scene.control.Button("Atualizar");
        refresh.setId("mcp-refresh");
        refresh.setOnAction(event -> actions.loadMcp());
        Runnable render = () -> {
            list.getChildren().clear();
            if (state.mcpServers().isEmpty()) {
                list.getChildren().add(muted("Nenhum servidor declarado no config.toml."));
            }
            for (Map<String, Object> server : state.mcpServers()) {
                String name = String.valueOf(server.get("name"));
                int tools = server.get("tools") instanceof List<?> declared ? declared.size() : 0;
                Object error = server.get("error");
                Label line = new Label(name + " · " + stateLabel(String.valueOf(server.get("state"))) + " · " + tools
                        + " ferramentas" + (error == null ? "" : " · " + error));
                line.setWrapText(true);
                javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, line);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                javafx.scene.layout.HBox.setHgrow(line, javafx.scene.layout.Priority.ALWAYS);
                if (Boolean.TRUE.equals(server.get("drift"))) {
                    javafx.scene.control.Button approve = new javafx.scene.control.Button("Aprovar mudança");
                    approve.setId("mcp-approve-" + name);
                    approve.setOnAction(event -> actions.approveMcp(name));
                    row.getChildren().add(approve);
                }
                list.getChildren().add(row);
            }
        };
        state.mcpServers().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        Label hint = muted("Servidores MCP trazem ferramentas novas sem mudar o Zordon. Se um servidor mudar o que"
                + " oferece, as ferramentas dele ficam suspensas até você aprovar aqui.");
        return HomeView.section("Servidores MCP", new VBox(10, hint, list, refresh));
    }

    static String stateLabel(String state) {
        return switch (state) {
            case "connected" -> "conectado";
            case "starting" -> "iniciando";
            case "reconnecting" -> "reconectando";
            case "drift" -> "mudou, aguardando aprovação";
            case "failed" -> "falhou";
            default -> "parado";
        };
    }

    /**
     * O modo técnico (SPEC-012 CA-8): o único caminho para Painel, Logs, Diagnóstico e
     * os metadados das respostas. Voice-first: desligado por padrão (ADR-0029).
     */
    private static javafx.scene.Node technicalMode(DesktopState state) {
        ToggleButton toggle = new ToggleButton();
        toggle.setId("technical-mode");
        toggle.getStyleClass().add("mode-option");
        toggle.selectedProperty().bindBidirectional(state.technicalModeProperty());
        toggle.textProperty().bind(javafx.beans.binding.Bindings.when(toggle.selectedProperty())
                .then("Modo técnico ligado").otherwise("Ligar modo técnico"));
        Label hint = new Label("Mostra o Painel, os Logs, o Diagnóstico e os detalhes técnicos das respostas."
                + " O registro completo fica sempre em ~/.zordon/trace.");
        hint.setWrapText(true);
        hint.getStyleClass().add("muted");
        return HomeView.section("Modo técnico", new VBox(10, toggle, hint));
    }

    private void showLevel(double[] level) {
        if (level == null || liveMeter == null) {
            return;
        }
        liveMeter.setProgress(VoicePresentation.meter(level[0]));
        liveLevel.setText(VoicePresentation.dbfs(level[0]) + " · pico " + VoicePresentation.dbfs(level[1]));
    }

    /**
     * Teste do microfone (SPEC-009): o medidor mostra o nível que o núcleo recebe,
     * e não um sinal local; é a prova de que o caminho host → núcleo funciona.
     */
    private Node microphoneTest(VoiceStatus voice) {
        VoicePresentation.TestCard card = VoicePresentation.testCard(voice);
        Button start = new Button(VoicePresentation.TEST_BUTTON);
        start.setId("microphone-test");
        start.getStyleClass().add("button-secondary");
        start.setDisable(!card.enabled());
        start.setOnAction(event -> actions.testMicrophone());

        ProgressBar meter = new ProgressBar(0);
        meter.setId("microphone-meter");
        meter.getStyleClass().add("microphone-meter");
        meter.setMaxWidth(Double.MAX_VALUE);
        meter.setAccessibleText("Nível do microfone");
        Label level = new Label("—");
        level.getStyleClass().add("row-value");
        boolean testing = voice != null && voice.testing();
        meter.setVisible(testing);
        meter.setManaged(testing);
        level.setVisible(testing);
        level.setManaged(testing);
        // O listener único do construtor atualiza estes dois enquanto forem os atuais.
        liveMeter = testing ? meter : null;
        liveLevel = testing ? level : null;

        Label hint = muted(card.hint());
        VBox body = new VBox(8, new HBox(12, start, hint), meter, level);
        if (!card.result().isEmpty()) {
            Label result = new Label(card.result());
            result.setId("microphone-result");
            result.setWrapText(true);
            result.getStyleClass().add(card.resultOk() ? "body-text" : "warning-text");
            body.getChildren().add(result);
        }
        return HomeView.section("Teste do microfone", body);
    }

    private Node mode(VoiceStatus voice, VoicePresentation.Screen screen) {
        ToggleGroup group = new ToggleGroup();
        FlowPane options = new FlowPane(6, 8);
        options.setPrefWrapLength(450);
        for (String mode : MODES) {
            ToggleButton option = new ToggleButton(VoicePresentation.modeLabel(mode));
            option.getStyleClass().add("mode-option");
            option.setToggleGroup(group);
            option.setSelected(voice != null && mode.equals(voice.mode()));
            option.setDisable(voice == null);
            option.setOnAction(event -> {
                // Keep selection authoritative, even if a selected toggle is clicked twice.
                group.selectToggle(group.getToggles().stream()
                        .filter(toggle -> ((ToggleButton) toggle).getText()
                                .equals(VoicePresentation.modeLabel(voice.mode()))).findFirst().orElse(null));
                actions.setVoiceMode(mode);
            });
            options.getChildren().add(option);
        }
        VBox choice = new VBox(8, new Label("Modo"), options);
        String microphone = voice == null ? "Estado desconhecido"
                : !voice.hostConnected() ? "Não conectado"
                : "on".equals(voice.capture()) ? "Ligado · confirmado"
                : "off".equals(voice.capture()) ? "Desligado · confirmado" : screen.capture();
        String engine = screen == null ? "Estado desconhecido" : screen.engine();
        FlowPane strip = new FlowPane(12, 12, choice,
                summary("Microfone", microphone), summary("Motor de voz", engine));
        strip.getStyleClass().add("voice-mode-strip");
        return strip;
    }

    private static Node summary(String title, String value) {
        Label label = new Label(title);
        label.getStyleClass().add("voice-caption");
        Label status = new Label("●  " + value);
        status.setWrapText(true);
        status.getStyleClass().add("voice-caption");
        VBox box = new VBox(7, label, status);
        box.setPrefWidth(122);
        box.setMaxWidth(160);
        box.getStyleClass().add("voice-status-card");
        return box;
    }



    private Node microphone(VoiceStatus voice, VoicePresentation.Screen screen) {
        Label capture = new Label(screen.capture());
        capture.setWrapText(true);
        boolean unconfirmed = voice.hostConnected() && !"on".equals(voice.capture()) && !"off".equals(voice.capture());
        capture.getStyleClass().add(unconfirmed ? "warning-text" : "body-text");

        ComboBox<VoiceDevice> devices = new ComboBox<>();
        devices.getItems().setAll(state.voiceDevices());
        devices.setPromptText(voice.hostConnected() ? "Atualize para listar" : "Sem host do Windows");
        state.voiceDevices().stream().filter(VoiceDevice::selected).findFirst().ifPresent(devices::setValue);
        devices.setDisable(!voice.hostConnected() || state.voiceDevices().isEmpty());
        devices.setOnAction(event -> {
            VoiceDevice chosen = devices.getValue();
            if (chosen != null && !chosen.selected()) {
                actions.selectVoiceDevice(chosen.id());
            }
        });
        Button refresh = new Button("Atualizar lista");
        refresh.getStyleClass().add("button-secondary");
        refresh.setDisable(!voice.hostConnected());
        refresh.setOnAction(event -> actions.loadVoiceDevices());
        HBox picker = new HBox(8, devices, refresh);

        return HomeView.section("Microfone", new VBox(12,
                capture,
                Inspector.rows("Host do Windows", screen.host(), "Dispositivo", screen.device()),
                picker));
    }

    private Node transcriptions() {
        VBox list = new VBox(6);
        if (state.transcriptions().isEmpty()) {
            list.getChildren().add(muted("Nenhuma transcrição nesta sessão."));
        } else {
            state.transcriptions().forEach(line -> {
                Label entry = new Label(line);
                entry.setWrapText(true);
                entry.getStyleClass().add("row-value");
                list.getChildren().add(entry);
            });
        }
        return HomeView.section("Transcrições desta sessão", list);
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("muted");
        return label;
    }

    private static Label warning(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("warning-text");
        return label;
    }
}
