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

import java.util.Set;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;
import zordon.core.zwp.ZwpServer;
import zordon.api.event.Topic;

/** Liga os dois fluxos de eventos do núcleo ao servidor ZWP. */
final class CoreEventForwarder {

    static void forward(ZordonEventBus bus, ZwpServer server) {
        Set<String> droppable = Topic.ALL.stream()
                .filter(topic -> !Topic.MANDATORY.contains(topic))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        bus.subscribe("zwp-mandatory", Topic.MANDATORY, QueuePolicy.rejectPublish(512), server::broadcastEvent);
        bus.subscribe("zwp", droppable, QueuePolicy.dropOldest(1_024), server::broadcastEvent);
    }

    private CoreEventForwarder() {}
}
