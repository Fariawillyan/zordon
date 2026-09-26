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
package zordon.core;

import java.util.List;
import zordon.api.security.Severity;
import zordon.core.notify.NotificationCenter;
import zordon.memory.TaskStore;

/** Tarefas que o núcleo deixou pela metade: bloqueadas e avisadas, nunca reexecutadas sozinhas (SPEC-023 CA-4). */
final class InterruptedTasks {

    private InterruptedTasks() {}

    static void announce(List<TaskStore.TaskView> interrupted, NotificationCenter notifications) {
        for (TaskStore.TaskView task : interrupted) {
            notifications.publish(notifications.message(new NotificationCenter.MessageFields(
                    Severity.WARNING, "SYSTEM",
                    "Uma tarefa foi interrompida",
                    "O núcleo reiniciou no meio da tarefa \"" + task.goal() + "\".",
                    "Uma etapa pode ter feito só metade; repetir sozinho poderia duplicar um efeito.",
                    "retomada de tarefas na inicialização (SPEC-023)",
                    "A tarefa ficou bloqueada; nada foi reexecutado.",
                    "tarefa " + task.id(), true,
                    "Bloqueada, esperando você.",
                    List.of("Continuar pela tela", "Cancelar"))));
        }
    }
}
