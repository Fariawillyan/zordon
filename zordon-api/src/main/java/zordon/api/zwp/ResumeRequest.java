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
package zordon.api.zwp;

import java.util.Objects;

/**
 * Pedido de retomada na reconexão (docs/api/zwp-protocol.md §8).
 *
 * <p>O {@code startId} identifica a execução do núcleo. Se ele mudou, o núcleo
 * reiniciou e não existe continuidade a retomar — o cliente descarta o estado
 * volátil em vez de assumir que ainda vale.
 */
public record ResumeRequest(String startId, long lastEventSeq) {

    public ResumeRequest {
        Objects.requireNonNull(startId, "startId");
        if (lastEventSeq < 0) {
            throw new IllegalArgumentException("lastEventSeq não pode ser negativo");
        }
    }
}
