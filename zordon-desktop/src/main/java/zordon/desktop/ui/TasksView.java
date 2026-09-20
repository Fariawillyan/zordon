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
        super("RECURSOS", "Tarefas", actions::loadTasks);
        this.state = state;
        this.actions = actions;
        setId("tasks-view");
        repaintOn(state.tasks());
        render();
    }

    @Override
    void render() {
        show(VoiceSettingsView.tasks(state, actions));
    }
}
