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
package zordon.host;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Map;
import zordon.api.trace.Spec;
import zordon.zwp.CoreConnection;

/** Notificação pela bandeja do Windows, mesmo com o desktop fechado. */
@Spec("SPEC-025")
public final class WindowsNotifications implements AutoCloseable {
    @FunctionalInterface
    public interface Delivery { boolean show(String title, String body, String severity) throws Exception; }
    private final Delivery delivery;
    private TrayIcon icon;

    public WindowsNotifications(Delivery delivery) { this.delivery = delivery; }
    private WindowsNotifications() { this.delivery = this::display; }
    public static WindowsNotifications system() { return new WindowsNotifications(); }

    public void registerOn(CoreConnection connection) {
        connection.handle("windows.notify", this::notify);
    }

    Map<String, Object> notify(Map<String, Object> params) {
        if (!(params.get("title") instanceof String title) || title.isBlank()
                || title.length() > 1000 || !(params.get("body") instanceof String body) || body.length() > 20_000) {
            return Map.of("shown", false, "reason", "título ou corpo inválido");
        }
        String severity = String.valueOf(params.getOrDefault("severity", "info"));
        try {
            return Map.of("shown", delivery.show(title, body, severity));
        } catch (Exception e) {
            return Map.of("shown", false, "reason", "bandeja indisponível");
        }
    }

    private boolean display(String title, String body, String severity) throws Exception {
        if (!SystemTray.isSupported()) {
            return false;
        }
        EventQueue.invokeAndWait(() -> {
            if (icon == null) {
                BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics();
                graphics.setColor(new Color(88, 180, 220));
                graphics.fillOval(2, 2, 28, 28);
                graphics.dispose();
                TrayIcon created = new TrayIcon(image, "Zordon");
                created.setImageAutoSize(true);
                try {
                    SystemTray.getSystemTray().add(created);
                    icon = created;
                } catch (java.awt.AWTException e) {
                    throw new IllegalStateException(e);
                }
            }
            icon.displayMessage(title, body, "info".equals(severity) ? TrayIcon.MessageType.INFO : TrayIcon.MessageType.WARNING);
        });
        return true;
    }

    @Override
    public void close() {
        EventQueue.invokeLater(() -> {
            if (icon != null) {
                SystemTray.getSystemTray().remove(icon);
                icon = null;
            }
        });
    }
}
