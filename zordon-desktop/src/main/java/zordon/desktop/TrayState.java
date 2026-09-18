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
package zordon.desktop;

import java.awt.Color;

/**
 * Estado que o ícone da bandeja comunica
 * ([UI §5](../../../../../docs/specs/ui/design.md#5-system-tray)).
 *
 * <p>As cores são as do design system. Estado nunca é comunicado só por cor: o
 * texto da dica acompanha sempre.
 */
enum TrayState {
    ONLINE(new Color(0x3D, 0xDC, 0xFF), "Zordon — núcleo online"),
    PROCESSING(new Color(0xA7, 0x8B, 0xFA), "Zordon — processando"),
    DEGRADED(new Color(0xF0, 0xB3, 0x41), "Zordon — degradado"),
    OFFLINE(new Color(0xFF, 0x5B, 0x6B), "Zordon — núcleo offline");

    private final Color color;
    private final String tooltip;

    TrayState(Color color, String tooltip) {
        this.color = color;
        this.tooltip = tooltip;
    }

    Color color() {
        return color;
    }

    String tooltip() {
        return tooltip;
    }
}
