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
package zordon.core.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import zordon.api.security.Severity;
import zordon.core.notify.NotificationCenter;
import zordon.core.notify.ZordonMessage;

/** Fila durável primeiro; o host é uma entrega adicional e opcional. */
public final class AutomationNotifier implements WorkflowEngine.Notifier {
    @FunctionalInterface
    public interface NativeDelivery { void send(String title, String body, String severity); }
    private record Key(String automation, String title, String body, String severity) {}
    private record Group(String id, Instant since, int count) {}
    private final Map<Key, Group> groups = new HashMap<>();
    private final NotificationCenter notifications;
    private final NativeDelivery nativeDelivery;
    private final Clock clock;

    public AutomationNotifier(NotificationCenter notifications, NativeDelivery nativeDelivery, Clock clock) {
        this.notifications = notifications;
        this.nativeDelivery = nativeDelivery;
        this.clock = clock;
    }

    @Override
    public synchronized void notify(AutomationSpec spec, String title, String body, String severity) {
        Instant now = clock.instant();
        groups.values().removeIf(group -> Duration.between(group.since(), now).compareTo(Duration.ofMinutes(10)) >= 0);
        Key key = new Key(spec.id(), title, body, severity);
        Group old = groups.get(key);
        Group group = old == null ? new Group("msg-" + UUID.randomUUID(), now, 1)
                : new Group(old.id(), old.since(), old.count() + 1);
        String shown = title + (group.count() == 1 ? "" : " (" + group.count() + "× desde "
                + DateTimeFormatter.ofPattern("HH:mm").withZone(clock.getZone()).format(group.since()) + ")");
        notifications.publishGrouped(new ZordonMessage(group.id(), Severity.valueOf(severity.toUpperCase(Locale.ROOT)),
                "SYSTEM", shown, body == null || body.isBlank() ? title : body, "Regra aprovada pelo usuário",
                "automation:" + spec.id(), "Aviso registrado", spec.name(), true, "Disponível na fila de avisos",
                List.of("Ver automações", "Desativar automação"), null, now));
        groups.put(key, group);
        // Falha da entrega adicional não pode causar retry e duplicação do aviso durável.
        try {
            nativeDelivery.send(shown, body == null ? "" : body, severity);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(AutomationNotifier.class).debug("aviso nativo indisponível: {}", e.toString());
        }
    }
}
