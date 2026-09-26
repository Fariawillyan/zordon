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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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


    private final VBox content = new VBox(12);
    private final Map<String, VBox> sections = new LinkedHashMap<>();
    private final Map<String, ToggleButton> sectionButtons = new LinkedHashMap<>();
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
        content.getChildren().addAll(modeArea, details);
        // Sem isto, o conteúdo dita a largura mínima e o console transborda na
        // janela de 720 (SPEC-010 CA-2). O laço das abas fazia isso por seção.
        content.setMinWidth(0);
        modeArea.setMinWidth(0);
        details.setMinWidth(0);
        setMinWidth(0);
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

    static Node notifications(DesktopState state, ShellActions actions) {
        VBox list = new VBox(6);
        list.setId("notification-list");
        Runnable render = () -> {
            list.getChildren().clear();
            if (state.notifications().isEmpty()) list.getChildren().add(muted("Nenhum aviso pendente."));
            for (Map<String, Object> message : state.notifications()) {
                String id = String.valueOf(message.get("messageId"));
                Label title = new Label(String.valueOf(message.getOrDefault("title", "Aviso")));
                title.getStyleClass().add("card-title");
                title.setWrapText(true);
                VBox body = new VBox(5, title);
                for (String key : List.of("whatHappened", "whySuspicious", "detectedBy", "affectedResource",
                        "actionTaken", "currentState")) {
                    Object value = message.get(key);
                    if (value != null && !value.toString().isBlank()) body.getChildren().add(muted(value.toString()));
                }
                Button read = new Button("Entendi");
                read.setId("notification-read-" + id);
                read.setOnAction(event -> {
                    state.acknowledged(id);
                    actions.acknowledge(id);
                });
                body.getChildren().add(read);
                body.getStyleClass().add("settings-row");
                list.getChildren().add(body);
            }
        };
        state.notifications().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        return Cards.section("Avisos", boundedList(list, 230));
    }

    /** Listas extensas têm rolagem própria, sem empurrar todos os outros controles. */
    private static ScrollPane boundedList(VBox list, double height) {
        ScrollPane scroll = new ScrollPane(list) {
            @Override
            public javafx.geometry.Orientation getContentBias() {
                return javafx.geometry.Orientation.HORIZONTAL;
            }

            @Override
            protected double computePrefHeight(double width) {
                double available = width < 0 ? list.prefWidth(-1) : Math.max(1, width - 16);
                return Math.min(height, list.prefHeight(available) + 2);
            }
        };
        scroll.getStyleClass().add("settings-list");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0);
        scroll.setMaxHeight(height);
        return scroll;
    }

    private static VBox section(String title, VBox body, Button refresh) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(10, heading, spacer, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(8, header, body);
        card.getStyleClass().add("card");
        return card;
    }

    private static HBox row(Label line, Button... buttons) {
        line.setMinWidth(0);
        line.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(line, Priority.ALWAYS);
        HBox row = new HBox(10, line);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        for (Button button : buttons) {
            button.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(button);
        }
        return row;
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
                Cards.section("Motor de voz", new VBox(8,
                        Inspector.rows("Estado", screen.engine()),
                        muted("VAD, palavra de ativação, transcrição e fala. Chega com o sidecar zordon-voice."))),
                transcriptions());
    }

    /** O kill switch (SPEC-015 CA-6): pausar é um clique; retomar também, mas só daqui ou da bandeja. */
    static javafx.scene.Node security(DesktopState state, ShellActions actions) {
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
                javafx.scene.control.Button seen = new javafx.scene.control.Button("Entendi");
                seen.setId("finding-ack-" + id);
                seen.setOnAction(event -> actions.acknowledgeFinding(id));
                HBox row = row(line, seen);
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
                Label line = new Label(subject + " · " + VoiceSettingsLabels.breaker(String.valueOf(breaker.get("state"))) + " · "
                        + breaker.get("reason"));
                line.setWrapText(true);
                javafx.scene.control.Button supervised = new javafx.scene.control.Button("Liberar supervisionado");
                supervised.setId("breaker-supervised-" + subject);
                supervised.setOnAction(event -> actions.releaseBreaker(subject, "supervised"));
                javafx.scene.control.Button closed = new javafx.scene.control.Button("Liberar");
                closed.setId("breaker-closed-" + subject);
                closed.setOnAction(event -> actions.releaseBreaker(subject, "closed"));
                HBox row = row(line, supervised, closed);
                open.getChildren().add(row);
            }
        };
        state.breakers().addListener((ListChangeListener<Map<String, Object>>) change -> breakers.run());
        breakers.run();
        Label hint = muted("O que a defesa achou: cada item diz por que foi considerado suspeito. Disjuntor aberto"
                + " só fecha por decisão sua.");
        return section("Proteção", new VBox(8, status, toggle, hint, open, boundedList(list, 260)), refresh);
    }

    /** Mínimo da senha mestre (SPEC-036 §10); o núcleo reforça, isto é cortesia. */
    private static final int OPPRESSOR_MIN = 12;

    /**
     * OPPRESSOR MODE (SPEC-036): a senha mestre, o liga/desliga e a troca.
     *
     * <p>É a única porta do modo pela janela, e o texto diz sem rodeio o que
     * ele faz: ligar isto tira o motor de permissão do caminho de toda ação. O
     * corpo é reconstruído a cada mudança de estado, como os achados e os
     * disjuntores acima.
     */
    static javafx.scene.Node oppressor(DesktopState state, ShellActions actions) {
        Label status = new Label();
        status.setWrapText(true);
        status.setId("oppressor-status");
        // A falha mora aqui, ao lado do botão que a causou: no chat ela sumia.
        Label failure = new Label();
        failure.setId("oppressor-failure");
        failure.getStyleClass().add("warning-text");
        failure.setWrapText(true);
        failure.textProperty().bind(state.oppressorErrorProperty());
        failure.visibleProperty().bind(failure.textProperty().isNotEmpty());
        failure.managedProperty().bind(failure.visibleProperty());
        VBox controls = new VBox(8);
        controls.setId("oppressor-controls");

        Runnable render = () -> {
            controls.getChildren().clear();
            if (state.oppressorProperty().get()) {
                status.setText("Ativo. Toda ordem executa direto: sem confirmação e sem teto por origem. "
                        + "O lockdown ainda vale, e sair volta tudo ao normal.");
                Button exit = new Button("Sair do OPPRESSOR MODE");
                exit.setId("oppressor-exit");
                exit.getStyleClass().setAll("button", "button-primary");
                exit.setOnAction(event -> actions.exitOppressor());
                controls.getChildren().add(exit);
                return;
            }
            boolean configured = state.oppressorConfiguredProperty().get();
            status.setText(configured
                    ? "Desligado. Com a senha mestre, sua ordem executa direto — sem as perguntas de permissão."
                    : "Desligado e sem senha mestre. Defina uma senha para poder ativar.");
            if (configured) {
                controls.getChildren().add(oppressorEnter(actions));
            }
            controls.getChildren().add(oppressorPassword(actions, configured));
        };
        state.oppressorProperty().addListener((observable, before, now) -> render.run());
        state.oppressorConfiguredProperty().addListener((observable, before, now) -> render.run());
        render.run();
        return Cards.section("OPPRESSOR MODE", new VBox(8, status, failure, controls));
    }

    /** O campo da senha e o botão de entrar. */
    private static javafx.scene.Node oppressorEnter(ShellActions actions) {
        PasswordField key = new PasswordField();
        key.setId("oppressor-key");
        key.setPromptText("senha mestre");
        HBox.setHgrow(key, Priority.ALWAYS);
        Button enter = new Button("Entrar");
        enter.setId("oppressor-enter");
        enter.getStyleClass().setAll("button", "button-danger");
        Runnable go = () -> {
            actions.enterOppressor(key.getText().toCharArray());
            key.clear();
        };
        enter.setOnAction(event -> go.run());
        key.setOnAction(event -> go.run());
        return new HBox(8, key, enter);
    }

    /** Cadastrar ou trocar a senha, com a validação que o núcleo também faz. */
    private static javafx.scene.Node oppressorPassword(ShellActions actions, boolean configured) {
        PasswordField current = new PasswordField();
        current.setId("oppressor-current");
        current.setPromptText("senha atual");
        PasswordField next = new PasswordField();
        next.setId("oppressor-next");
        next.setPromptText("nova senha (mín. " + OPPRESSOR_MIN + ")");
        PasswordField confirm = new PasswordField();
        confirm.setId("oppressor-confirm");
        confirm.setPromptText("repita a nova senha");
        Label problem = new Label();
        problem.setId("oppressor-problem");
        problem.getStyleClass().add("warning-text");
        problem.setWrapText(true);
        problem.setVisible(false);
        problem.managedProperty().bind(problem.visibleProperty());
        Button save = new Button(configured ? "Trocar senha" : "Definir senha");
        save.setId("oppressor-save");
        save.setOnAction(event -> {
            String candidate = next.getText();
            if (candidate.length() < OPPRESSOR_MIN) {
                warn(problem, "A senha mestre tem no mínimo " + OPPRESSOR_MIN + " caracteres.");
                return;
            }
            if (!candidate.equals(confirm.getText())) {
                warn(problem, "As senhas não conferem.");
                return;
            }
            problem.setVisible(false);
            actions.setOppressorPassword(current.getText().toCharArray(), candidate.toCharArray());
            current.clear();
            next.clear();
            confirm.clear();
        });
        VBox box = new VBox(8, muted(configured ? "Trocar a senha mestre:" : "Definir a senha mestre:"));
        if (configured) {
            box.getChildren().add(current);
        }
        box.getChildren().addAll(next, confirm, problem, save);
        return box;
    }

    private static void warn(Label label, String text) {
        label.setText(text);
        label.setVisible(true);
    }

    /** A quarentena (SPEC-017): o que foi "apagado" continua aqui até ser restaurado. */
    static javafx.scene.Node quarantine(DesktopState state, ShellActions actions) {
        VBox list = new VBox(8);
        list.setId("quarantine-list");
        Label notice = new Label();
        notice.setWrapText(true);
        notice.textProperty().bind(state.quarantineNoticeProperty());
        notice.visibleProperty().bind(notice.textProperty().isNotEmpty());
        notice.managedProperty().bind(notice.visibleProperty());
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
                HBox row = row(line, restore);
                list.getChildren().add(row);
            }
        };
        state.quarantine().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        Label hint = muted("O Zordon não apaga: o que você mandou para a quarentena fica guardado e volta com um clique.");
        return section("Quarentena", new VBox(8, hint, list, notice), refresh);
    }


    /** As tarefas (SPEC-023): etapas, o que espera você e o que foi interrompido. */
    static javafx.scene.Node tasks(DesktopState state, ShellActions actions) {
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
                Label goal = new Label(task.get("goal") + " · " + VoiceSettingsLabels.task(String.valueOf(task.get("state")))
                        + (task.get("reason") instanceof String reason ? " · " + reason : ""));
                goal.setWrapText(true);
                box.getChildren().add(goal);
                if (task.get("stepList") instanceof List<?> steps) {
                    for (Object item : steps) {
                        if (!(item instanceof Map<?, ?> step)) {
                            continue;
                        }
                        String stepId = String.valueOf(step.get("id"));
                        Label line = new Label("  " + VoiceSettingsLabels.task(String.valueOf(step.get("state"))) + " · "
                                + step.get("title"));
                        line.setWrapText(true);
                        HBox row = row(line);
                        if ("waiting_human".equals(step.get("state"))) {
                            javafx.scene.control.Button pass = new javafx.scene.control.Button("Confirmar");
                            pass.setId("confirm-" + taskId + "-" + stepId);
                            pass.setOnAction(event -> actions.confirmStep(taskId, stepId, true));
                            javafx.scene.control.Button fail = new javafx.scene.control.Button("Reprovar");
                            fail.setId("reject-" + taskId + "-" + stepId);
                            fail.setOnAction(event -> actions.confirmStep(taskId, stepId, false));
                            pass.setMinWidth(Region.USE_PREF_SIZE);
                            fail.setMinWidth(Region.USE_PREF_SIZE);
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
        return section("Tarefas", new VBox(8, hint, list), refresh);
    }

    static javafx.scene.Node automations(DesktopState state, ShellActions actions) {
        VBox list = new VBox(12);
        list.setId("automation-list");
        javafx.scene.control.Button refresh = new javafx.scene.control.Button("Atualizar");
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
        VBox controls = section("Automações", new VBox(8, list), refresh);
        controls.disableProperty().bind(state.connectionProperty().isNotEqualTo(zordon.zwp.CoreConnection.State.ONLINE));
        return controls;
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
        VBox body = new VBox(8, row(hint, start), meter, level);
        if (!card.result().isEmpty()) {
            Label result = new Label(card.result());
            result.setId("microphone-result");
            result.setWrapText(true);
            result.getStyleClass().add(card.resultOk() ? "body-text" : "warning-text");
            body.getChildren().add(result);
        }
        return Cards.section("Teste do microfone", body);
    }

    private Node mode(VoiceStatus voice, VoicePresentation.Screen screen) {
        // Um seletor só, usado aqui e no painel do console (SPEC-033).
        Node choice = new VoiceModePicker(state, actions, false);
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

        return Cards.section("Microfone", new VBox(12,
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
        return Cards.section("Transcrições desta sessão", list);
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
