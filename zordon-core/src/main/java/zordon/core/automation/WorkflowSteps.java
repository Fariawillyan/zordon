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
package zordon.core.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.core.tools.SkillRuntime;

/** Um passo do fluxo pelo que ele é: ferramenta pelo caminho mediado, agente ou aviso. */
final class WorkflowSteps {

    private final SkillRuntime tools;
    private final WorkflowEngine.Notifier notifier;
    private final AgentStep agentStep;

    WorkflowSteps(SkillRuntime tools, WorkflowEngine.Notifier notifier, AgentStep agentStep) {
        this.tools = tools;
        this.notifier = notifier;
        this.agentStep = agentStep;
    }

    long tokensToday() {
        return agentStep.tokensToday();
    }

    Map<String, Object> run(AutomationSpec spec, AutomationSpec.Step step, String taskId,
            Map<String, Map<String, Object>> context) throws Exception {
        if ("agent".equals(step.kind())) {
            return agentStep.run(spec, step, taskId, context);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        switch (step.kind()) {
            case "tool" -> {
                Map<String, Object> args = Expressions.render(step.args(), context);
                SkillRuntime.Outcome outcome = tools.invokeForAutomation(step.tool(), args,
                        new Principal("automation:" + spec.id(), RequestOrigin.AUTOMATION, false),
                        taskId + "/" + step.id(), spec.toolScope())
                        .get(spec.limits().timeout().toMillis(), TimeUnit.MILLISECONDS);
                out.putAll(outcome.result().data());
                out.put("text", outcome.result().text());
                out.putIfAbsent("ok", outcome.ok());
                if (!outcome.ok()) {
                    out.put("error", outcome.result().text());
                }
            }
            default -> {
                String title = Expressions.render(step.message().title(), context);
                String body = Expressions.render(step.message().body(), context);
                notifier.notify(spec, title, body, step.message().severity());
                out.put("title", title);
                out.put("text", body);
                out.put("ok", true);
            }
        }
        return out;
    }
}
