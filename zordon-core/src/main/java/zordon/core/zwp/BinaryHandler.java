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
package zordon.core.zwp;

import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;

/** Quem recebe os frames binários que chegam ao servidor (SPEC-009). */
@Spec("SPEC-009")
public interface BinaryHandler {

    void frame(ZwpSession session, BinaryFrame frame);

    /** Bytes que não formam um frame: magia errada, cabeçalho curto, tipo desconhecido. */
    void malformed(ZwpSession session, String reason);
}
