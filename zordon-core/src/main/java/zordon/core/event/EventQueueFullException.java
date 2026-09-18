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
package zordon.core.event;

/**
 * Fila de um assinante com política {@code REJECT_PUBLISH} encheu.
 *
 * <p>Nunca deve acontecer em uso normal. Quando acontece, falhar alto é melhor do
 * que perder um pedido de autorização ou um evento de segurança.
 */
public class EventQueueFullException extends RuntimeException {

    public EventQueueFullException(String message) {
        super(message);
    }
}
