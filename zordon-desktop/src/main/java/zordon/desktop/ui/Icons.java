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
package zordon.desktop.ui;

import java.util.Map;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * Ícones lineares em grade 24, os mesmos da
 * [prévia](../../../../../../../docs/specs/ui/preview/index.html).
 *
 * <p>Os caminhos são copiados de {@code preview.js} para que a janela e a prévia
 * não divirjam: o design system pede geometria consistente e nenhum emoji como
 * iconografia ([Design system §5](../../../../../../../docs/specs/ui/design-system.md#5-componentes)).
 */
public final class Icons {

    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("arrow", "M5 12h14M13 6l6 6-6 6"),
            Map.entry("attach", "M21 11l-8.5 8.5a5 5 0 0 1-7-7L14 4a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L16 6"),
            Map.entry("bell", "M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8M13.7 21a2 2 0 0 1-3.4 0"),
            Map.entry("chip", "M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3M6 6h12v12H6zM10 10h4v4h-4z"),
            Map.entry("close", "M6 6l12 12M18 6L6 18"),
            Map.entry("mic", "M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3M19 10v1a7 7 0 0 1-14 0v-1M12 18v4"),
            Map.entry("panel", "M3 4h18v16H3zM15 4v16"),
            Map.entry("pause", "M9 5v14M15 5v14"),
            Map.entry("plus", "M12 5v14M5 12h14"),
            Map.entry("settings", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 7.9 19.4a2 2 0 1 1-2.8-2.8l.1-.1A1.6 1.6 0 0 0 4.3 14H4a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.1-2.7 2 2 0 1 1 2.8-2.8l.1.1A1.6 1.6 0 0 0 10 4.6V4a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1"),
            Map.entry("shield", "M12 2l8 4v6c0 5-3.4 8.9-8 10-4.6-1.1-8-5-8-10V6zM9 12l2 2 4-4"),
            Map.entry("spark", "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z"),
            Map.entry("home", "M4 10l8-6 8 6v10H4zM10 20v-6h4v6"),
            Map.entry("chat", "M21 12a8 8 0 0 1-8 8H5l-2 2V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8"),
            Map.entry("plug", "M9 3v6M15 3v6M6 9h12v3a6 6 0 0 1-12 0zM12 18v3"),
            Map.entry("tool", "M14 6a4 4 0 0 1 5.5 5.2L21 13l-2 2-1.8-1.5A4 4 0 0 1 12 8zM12 12l-7 7 2 2 7-7"),
            Map.entry("clock", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18M12 7v5l3 2"),
            Map.entry("brain", "M8 4a3 3 0 0 0-3 3 3 3 0 0 0-1 5.8A3 3 0 0 0 7 18a3 3 0 0 0 5 1 3 3 0 0 0 5-1 3 3 0 0 0 3-5.2A3 3 0 0 0 19 7a3 3 0 0 0-3-3 3 3 0 0 0-4 1 3 3 0 0 0-4-1M12 5v14"),
            Map.entry("book", "M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2zM8 7h8M8 11h6"),
            Map.entry("cpu", "M6 6h12v12H6zM10 10h4v4h-4zM9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3"),
            Map.entry("coin", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18M12 7v10M9.5 9.5h4a1.5 1.5 0 0 1 0 3h-3a1.5 1.5 0 0 0 0 3h4"),
            Map.entry("list", "M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01"),
            Map.entry("pulse", "M3 12h4l3 8 4-16 3 8h4"),
            Map.entry("check", "M20 6L9 17l-5-5"),
            // Trilho da SPEC-010, desenhados para a imagem de referência.
            Map.entry("wave", "M3 10.5v3M6 8v8M9 5v14M12 9v6M15 6.5v11M18 9.5v5M21 11v2"),
            Map.entry("grid", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z"),
            Map.entry("doc", "M6 3h9l4 4v14H6zM9 10h7M9 14h7M9 18h4"),
            // Microfone riscado: o "desligar" da pílula, sem texto (SPEC-012 CA-7).
            Map.entry("micoff", "M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3M19 10v1a7 7 0 0 1-14 0v-1M12 18v4M3 3l18 18"));

    private Icons() {}

    /** Ícone de traço, no tamanho pedido (a grade de origem é 24). */
    public static SVGPath of(String name, double size, Paint color) {
        SVGPath icon = new SVGPath();
        icon.setContent(PATHS.getOrDefault(name, PATHS.get("check")));
        icon.setFill(Color.TRANSPARENT);
        icon.setStroke(color);
        icon.setStrokeWidth(1.75);
        icon.setStrokeLineCap(StrokeLineCap.ROUND);
        icon.setStrokeLineJoin(StrokeLineJoin.ROUND);
        double scale = size / 24.0;
        icon.setScaleX(scale);
        icon.setScaleY(scale);
        icon.getStyleClass().add("icon");
        return icon;
    }

    public static boolean exists(String name) {
        return PATHS.containsKey(name);
    }
}
