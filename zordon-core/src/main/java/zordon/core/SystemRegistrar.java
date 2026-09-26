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
package zordon.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import zordon.core.zwp.ChatMethods;
import zordon.core.zwp.SessionMethods;
import zordon.core.zwp.SystemMethods;

/** Os métodos do protocolo sobre o próprio núcleo: sessão, chat e o diagnóstico de cada subsistema. */
final class SystemRegistrar {

    /** O que este núcleo sabe fazer. Cresce por marco, e nunca antes de ser verdade. */
    private static final List<String> CAPABILITIES = List.of("chat.stream");

    private SystemRegistrar() {}

    static void register(CoreModules modules, String version, Instant startedAt) {
        CoreBase base = modules.base();
        new SessionMethods(base.bus(), version, startedAt, CAPABILITIES).registerOn(base.server());
        new ChatMethods(base.turns(), base.conversations()).registerOn(base.server());
        new SystemMethods(version, startedAt,
                        new SystemMethods.Runtime(base.bus(), base.turns(), base.providers(),
                                base.server()::connectedClients),
                        new SystemMethods.Reports(modules.voice().voice()::status, modules.voice()::traceReport,
                                () -> Map.of("audit", modules.trust().check().state(),
                                        "programs", base.settings().load().catalog().keySet().stream().sorted()
                                                .toList())))
                .with("rag", modules.tasks().knowledge()::status)
                .with("usage", () -> modules.tasks().usage().summary(7))
                .with("monitor", modules.monitor().methods()::status)
                .with("automation", modules.automation().automations()::diagnostics)
                .with("defense", modules.response()::diagnostics)
                .with("memory", modules.memory()::stats)
                .registerOn(base.server());
    }
}
