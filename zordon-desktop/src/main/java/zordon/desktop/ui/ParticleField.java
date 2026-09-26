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

import java.util.Random;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/** Renders the three particle planes around the orb. */
final class ParticleField {

    private ParticleField() {}

    static void draw(GraphicsContext g, double radius, VoiceVisualizer.ParticleState s) {
        Random random = new Random(19);
        for (int i = 0; i < 260; i++) {
            double x = (random.nextDouble() * VoiceVisualizer.W + Math.sin(s.t() * 0.12 + i) * 5
                    + VoiceVisualizer.W) % VoiceVisualizer.W;
            double y = (random.nextDouble() * VoiceVisualizer.H + Math.cos(s.t() * 0.08 + i) * 3
                    + VoiceVisualizer.H) % VoiceVisualizer.H;
            g.setFill(color("#2ACDE0", 0.08 + random.nextDouble() * 0.14));
            g.fillOval(x, y, 0.7, 0.7);
        }
        for (int i = 0; i < 1700; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double z = random.nextDouble() * 2 - 1;
            double orbit = radius * (0.78 + s.mid() * 0.18) * Math.sqrt(1 - z * z);
            double rotation = angle + s.phase() * 0.11 + spin(s) + s.bass() * 0.18;
            double x = orbit * Math.cos(rotation);
            double y = radius * z;
            double depth = Math.sin(rotation);
            g.setFill(color("#27EAFF", 0.07 + 0.34 * Math.abs(depth) + s.treble() * 0.1));
            double pull = "understanding".equals(s.activity()) ? 0.86 + 0.14 * Math.cos(s.t() * 3) : 1;
            double size = depth > 0.5 ? 1.4 : 0.8;
            g.fillOval(VoiceVisualizer.CX + x * pull, VoiceVisualizer.CY + y * pull, size, size);
        }
        for (int i = 0; i < 900; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = radius + random.nextGaussian() * (4.2 + s.treble() * 7);
            double drift = Math.sin(s.t() * 0.9 + i * 0.17) * (1 + s.mid() * 5);
            double size = 0.5 + random.nextDouble() * 1.6 + s.treble() * 0.8;
            g.setFill(color("#50EDFF", 0.14 + random.nextDouble() * 0.48 + s.treble() * 0.12));
            g.fillOval(VoiceVisualizer.CX + Math.cos(angle) * r + drift,
                    VoiceVisualizer.CY + Math.sin(angle) * r, size, size);
        }
    }

    private static double spin(VoiceVisualizer.ParticleState s) {
        return switch (s.activity()) {
            case "understanding" -> s.t() * 1.5;
            case "planning" -> s.t() * 0.3;
            case "executing" -> s.t() * 0.8;
            case "agents" -> s.t() * 0.5;
            case "listening" -> s.t() * (0.12 + s.mid() * 0.8) * s.intensity();
            case "speaking" -> s.t() * (0.35 + s.treble() * 1.6);
            default -> s.t() * 0.04 * s.intensity();
        };
    }

    private static Color color(String value, double opacity) { return Color.web(value, opacity); }
}
