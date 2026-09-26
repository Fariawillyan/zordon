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

import java.util.LinkedHashMap;
import java.util.Map;
import zordon.api.event.EventType;
import zordon.core.event.ZordonEventBus;

/** O {@code TASK_STATE} de cada mudança de uma tarefa ou etapa: é o que a tela e a voz acompanham. */
final class TaskEvents {

    private final ZordonEventBus bus;

    TaskEvents(ZordonEventBus bus) {
        this.bus = bus;
    }

    void publish(String taskId, String stepId, String state, String title, String goal, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        if (stepId != null) {
            payload.put("stepId", stepId);
        }
        payload.put("state", state);
        if (title != null) {
            payload.put("title", title);
        }
        if (goal != null) {
            payload.put("goal", goal);
        }
        if (reason != null) {
            payload.put("reason", reason);
        }
        bus.publish(EventType.TASK_STATE, payload);
    }
}
