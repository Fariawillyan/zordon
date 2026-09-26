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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import zordon.api.TurnId;

/** Os turnos em andamento, pelo id. */
final class RunningTurns {

    private final Map<TurnId, RunningTurn> running = new ConcurrentHashMap<>();

    void put(TurnId turn, RunningTurn handle) {
        running.put(turn, handle);
    }

    void remove(TurnId turn) {
        running.remove(turn);
    }

    boolean contains(TurnId turn) {
        return running.containsKey(turn);
    }

    List<String> ids() {
        return running.keySet().stream().map(TurnId::value).toList();
    }

    /** Cancela um turno específico. Cancelamento não é erro. @return se ele estava em andamento */
    boolean cancel(TurnId turn) {
        RunningTurn handle = running.remove(turn);
        if (handle == null) {
            return false;
        }
        handle.cancel();
        return true;
    }

    /** Cancela todos. @return quantos estavam em andamento */
    int cancelAll() {
        int cancelled = running.size();
        running.values().forEach(RunningTurn::cancel);
        running.clear();
        return cancelled;
    }
}
