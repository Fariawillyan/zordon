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

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.Circle;

/** A esfera é o botão de falar (SPEC-011 CA-7): invisível, circular, acessível. */
final class TalkOrb extends Button {

    TalkOrb() {
        setId("voice-talk");
        getStyleClass().add("orb-button");
        setAccessibleText("Falar com o Zordon");
        setTooltip(new Tooltip("Falar com o Zordon (Ctrl+Espaço)"));
    }

    /** Um círculo de {@code diameter} centrado em ({@code x}, {@code y}), sobre a esfera desenhada. */
    void place(double x, double y, double diameter) {
        setShape(new Circle(diameter / 2));
        resizeRelocate(x - diameter / 2, y - diameter / 2, diameter, diameter);
    }
}
