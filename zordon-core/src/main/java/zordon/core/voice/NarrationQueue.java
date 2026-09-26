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
package zordon.core.voice;

import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;
import zordon.core.activity.Narration;

/**
 * A fila de falas (SPEC-011 §3): no máximo {@link SpeechPlayer#QUEUE}; autorização
 * passa na frente de tudo e tira da fila as falas normais.
 */
final class NarrationQueue {

    private final LinkedBlockingDeque<Narration> queue = new LinkedBlockingDeque<>();

    /**
     * @param playing a fala tocando agora, ou {@code null}
     * @return se a fala em curso deve ser interrompida para esta passar
     */
    boolean offer(Narration narration, Narration playing) {
        if (narration.priority() == Narration.Priority.AUTHORIZATION) {
            boolean interrupt = playing != null && playing.priority() == Narration.Priority.NORMAL;
            queue.removeIf(queued -> queued.priority() == Narration.Priority.NORMAL);
            queue.addFirst(narration);
            return interrupt;
        }
        if (queue.size() >= SpeechPlayer.QUEUE) {
            // Cheia: uma normal sai para a mais nova entrar; se só há urgentes, a normal nova é que sobra.
            Iterator<Narration> oldest = queue.iterator();
            boolean freed = false;
            while (oldest.hasNext()) {
                if (oldest.next().priority() == Narration.Priority.NORMAL) {
                    oldest.remove();
                    freed = true;
                    break;
                }
            }
            if (!freed && narration.priority() == Narration.Priority.NORMAL) {
                return false;
            }
        }
        queue.addLast(narration);
        return false;
    }

    /** Põe na frente de tudo, sem repetir. */
    void first(Narration narration) {
        queue.remove(narration);
        queue.addFirst(narration);
    }

    void clear() {
        queue.clear();
    }

    int size() {
        return queue.size();
    }

    Narration take() throws InterruptedException {
        return queue.takeFirst();
    }
}
