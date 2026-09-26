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
package zordon.core.chat;

import zordon.ai.AiStream;
import zordon.api.security.RequestOrigin;
import zordon.core.agents.AgentProfile;
import zordon.core.agents.AgentRegistry;
import zordon.core.agents.TurnScope;

/**
 * Um turno em andamento, cancelável antes mesmo de o stream existir.
 *
 * <p>Sem isto havia uma janela entre o provider começar a transmitir e o turno
 * ser registrado: um cancelamento nesse intervalo se perdia, e o modelo seguia
 * gerando — e cobrando — enquanto a interface dizia que tinha parado. As duas
 * escritas voláteis garantem que, seja qual for a ordem entre anexar o stream e
 * cancelar, um dos lados vê o outro.
 */
final class RunningTurn {

    private volatile AiStream stream;
    private volatile boolean cancelled;
    /** De onde veio o turno ({@code voice} ou {@code text}): a origem das ferramentas que ele chamar. */
    volatile String source = "text";
    /** O agente do turno (SPEC-022); {@code null} sem registro de agentes. */
    volatile TurnScope scope;
    volatile String notice;
    int toolCalls;
    int steps;

    /** O turno de um pedido ao modelo, com o agente escolhido — ou o geral, com um aviso. */
    static RunningTurn of(Intent.Model model, String source, TurnHooks hooks) {
        RunningTurn handle = new RunningTurn();
        handle.source = source;
        AgentRegistry registry = hooks.agents();
        if (registry != null) {
            AgentProfile profile = registry.find(model.agentId()).orElse(null);
            if (profile == null) {
                handle.notice = "não conheço o agente " + model.agentId() + "; quem responde é o Zordon";
                profile = registry.general();
            }
            handle.scope = TurnScope.of(profile, "voice".equals(source) ? RequestOrigin.VOICE : RequestOrigin.UI,
                    hooks.nanos());
        }
        return handle;
    }

    void attach(AiStream attached) {
        stream = attached;
        if (cancelled) {
            attached.cancel();
        }
    }

    void cancel() {
        cancelled = true;
        AiStream current = stream;
        if (current != null) {
            current.cancel();
        }
    }

    boolean isCancelled() {
        return cancelled;
    }
}
