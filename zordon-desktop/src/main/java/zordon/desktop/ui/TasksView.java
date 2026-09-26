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

import java.util.List;
import java.util.Map;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;

/**
 * As Tarefas (SPEC-031): planos duráveis, com as etapas e a decisão que falta.
 *
 * <p>Era a metade da aba "Aplicativos" dos Ajustes. A montagem continua sendo a
 * mesma da SPEC-023 — só a casa mudou.
 */
@Spec("SPEC-031")
final class TasksView extends DestinationPage {

    private final DesktopState state;
    private final ShellActions actions;

    TasksView(DesktopState state, ShellActions actions) {
        super("RECURSOS", "Tarefas", actions.data()::loadTasks);
        this.state = state;
        this.actions = actions;
        setId("tasks-view");
        repaintOn(state.resources().tasks());
        render();
    }

    @Override
    void render() {
        show(card(state, actions));
    }

    /** As tarefas (SPEC-023): etapas, o que espera você e o que foi interrompido. */
    private static Node card(DesktopState state, ShellActions actions) {
        VBox list = new VBox(10);
        list.setId("task-list");
        Button refresh = new Button("Atualizar");
        refresh.setId("task-refresh");
        refresh.setOnAction(event -> actions.data().loadTasks());
        Runnable render = () -> {
            list.getChildren().clear();
            if (state.resources().tasks().isEmpty()) {
                list.getChildren().add(SettingsRows.muted("Nenhuma tarefa. Diga \"Zordon, planeje e faça: …\"."));
            }
            for (Map<String, Object> task : state.resources().tasks()) {
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
                        HBox row = SettingsRows.row(line);
                        if ("waiting_human".equals(step.get("state"))) {
                            Button pass = new Button("Confirmar");
                            pass.setId("confirm-" + taskId + "-" + stepId);
                            pass.setOnAction(event -> actions.data().confirmStep(taskId, stepId, true));
                            Button fail = new Button("Reprovar");
                            fail.setId("reject-" + taskId + "-" + stepId);
                            fail.setOnAction(event -> actions.data().confirmStep(taskId, stepId, false));
                            pass.setMinWidth(Region.USE_PREF_SIZE);
                            fail.setMinWidth(Region.USE_PREF_SIZE);
                            row.getChildren().addAll(pass, fail);
                        }
                        box.getChildren().add(row);
                    }
                }
                if ("blocked".equals(task.get("state"))) {
                    Button resume = new Button("Continuar");
                    resume.setId("resume-" + taskId);
                    resume.setOnAction(event -> actions.data().resumeTask(taskId));
                    box.getChildren().add(resume);
                }
                list.getChildren().add(box);
            }
        };
        state.resources().tasks().addListener((ListChangeListener<Map<String, Object>>) change -> render.run());
        render.run();
        Label hint = SettingsRows.muted("Uma etapa só conta como concluída depois de conferida. Tarefa interrompida espera você"
                + " decidir: nada é repetido sozinho.");
        return SettingsRows.section("Tarefas", new VBox(8, hint, list), refresh);
    }
}
