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

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import zordon.api.SessionId;
import zordon.api.TurnId;

/** A entrada de um turno: grava, publica e decide o caminho — resposta local, cancelar, ferramenta ou modelo. */
final class TurnIntake {

    private final TurnParts parts;
    private final IntentRouter router;
    private final TurnRouting routing;

    TurnIntake(TurnParts parts, IntentRouter router, TurnRouting routing) {
        this.parts = parts;
        this.router = router;
        this.routing = routing;
    }

    /** @param agent o agente escolhido na tela; forçar sempre vence o roteador (Agentes §6) */
    TurnId send(SessionId session, String text, String source, String agent) {
        TurnId turn = new TurnId("t_" + Long.toHexString(System.nanoTime()));
        parts.conversations().append(session, new StoredMessage("user", text, turn, Instant.now()));
        parts.events().userCommand(turn, session.value(), text, source);

        Intent routed = router.route(text);
        if (agent != null && !agent.isBlank() && routed instanceof Intent.Model) {
            routed = new Intent.Model(agent);
        }
        switch (routed) {
            case Intent.Immediate immediate -> parts.completion().answerLocally(session, turn, immediate);
            case Intent.CancelCurrent cancel -> cancelEverything(session, turn);
            case Intent.Tool tool -> Thread.ofVirtual().name("zordon-turn-" + turn.value())
                    .start(() -> useTool(session, turn, tool, source));
            case Intent.Model model -> {
                // Registrado aqui, na thread de quem pediu, antes de qualquer outra
                // começar: a partir deste ponto o turno já é cancelável.
                RunningTurn handle = RunningTurn.of(model, source, parts.hooks());
                parts.running().put(turn, handle);
                Thread.ofVirtual()
                        .name("zordon-turn-" + turn.value())
                        .start(() -> routing.ask(session, turn, model, handle));
            }
        }
        return turn;
    }

    private void cancelEverything(SessionId session, TurnId turn) {
        int cancelled = parts.running().cancelAll();
        String answer = cancelled == 0 ? "Não há nada em andamento." : "Cancelado.";
        parts.completion().answerLocally(session, turn, new Intent.Immediate(answer, "cancelar"));
    }

    /** Rota rápida de ferramenta: a resposta é o resultado dela, dito como qualquer resposta. */
    private void useTool(SessionId session, TurnId turn, Intent.Tool tool, String source) {
        String answer;
        try {
            answer = parts.hooks().tools().invoke(tool.tool(), tool.args(), source, turn.value())
                    .get(ToolRound.WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            answer = "Não consegui: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()) + ".";
        }
        parts.completion().answerLocally(session, turn, new Intent.Immediate(answer, tool.rule()));
    }
}
