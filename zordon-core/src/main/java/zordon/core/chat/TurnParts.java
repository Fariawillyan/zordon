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

import zordon.core.event.ZordonEventBus;

/** As partes que todo turno compartilha: a conversa, as ligações, os turnos em curso, os eventos e o fim. */
record TurnParts(ConversationStore conversations, TurnHooks hooks, RunningTurns running, TurnEvents events,
        TurnCompletion completion, TurnPrompt prompt) {

    static TurnParts of(ZordonEventBus bus, ConversationStore conversations, PromptComposer prompts, TurnHooks hooks,
            RunningTurns running) {
        TurnEvents events = new TurnEvents(bus);
        return new TurnParts(conversations, hooks, running, events,
                new TurnCompletion(conversations, hooks, running, events), new TurnPrompt(conversations, prompts, hooks));
    }
}
