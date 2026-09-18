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

/**
 * O que o cliente é. Determina o que ele pode oferecer e o que pode pedir: um
 * {@code desktop} não pode se registrar como provedor do WindowsBridge; um
 * {@code host} pode (docs/architecture/communication.md §4).
 */
public enum ClientKind {
    DESKTOP,
    HOST,
    CLI,
    TEST;

    /** Forma que viaja no protocolo — minúscula, como no JSON. */
    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
