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
import javafx.scene.shape.StrokeLineCap;

/** As ondas laterais, as barras de espectro nas pontas e o traço do som que está tocando. */
final class WaveField {

    private WaveField() {}

    static void draw(GraphicsContext g, VoiceVisualizer.WaveState s, VoiceVisualizer.Palette palette) {
        sideWaves(g, s, palette);
        bars(g, s, palette);
        sample(g, s, palette);
        g.setStroke(palette.col("#1CEBFF", 0.55));
        g.setLineWidth(1);
        g.strokeLine(0, VoiceVisualizer.CY, VoiceVisualizer.CX - 150, VoiceVisualizer.CY);
        g.strokeLine(VoiceVisualizer.CX + 150, VoiceVisualizer.CY, VoiceVisualizer.W, VoiceVisualizer.CY);
        // Marcadores curtos dos dois lados da esfera.
        g.setStroke(palette.col("#12E3F7", 1));
        g.setLineWidth(3.2);
        double marker = 18 + Math.min(22, (s.bass() + s.mid() + s.treble()) * 10);
        g.strokeLine(VoiceVisualizer.CX - 229, VoiceVisualizer.CY - marker, VoiceVisualizer.CX - 229,
                VoiceVisualizer.CY + marker);
        g.strokeLine(VoiceVisualizer.CX + 231, VoiceVisualizer.CY - marker, VoiceVisualizer.CX + 231,
                VoiceVisualizer.CY + marker);
    }

    /** Fios das ondas laterais: finos, somem perto da esfera e nas pontas. */
    private static void sideWaves(GraphicsContext g, VoiceVisualizer.WaveState s, VoiceVisualizer.Palette palette) {
        for (int wave = 0; wave < 26; wave++) {
            g.setStroke(palette.col("#12DDF2", 0.14 + (wave % 5) * 0.05));
            g.setLineWidth(0.8);
            g.beginPath();
            boolean started = false;
            for (int x = 0; x <= (int) VoiceVisualizer.W; x += 3) {
                double distance = Math.abs(x - VoiceVisualizer.CX);
                if (distance < 150) {
                    started = false;
                    continue;
                }
                double side = (distance - 150) / (VoiceVisualizer.CX - 150);
                double envelope = Math.pow(Math.sin(Math.min(1, side) * Math.PI), 1.2);
                double shape = Math.sin(x * 0.014 + wave * 0.11 + s.wavePhase()) * (0.55 + s.bass() * 1.7)
                        + Math.sin(x * 0.045 - wave * 0.17 + s.phase() * 2) * (0.25 + s.mid() * 0.95)
                        + Math.sin(x * 0.11 + wave * 0.33 + s.t() * 8) * (0.12 + s.treble() * 0.42);
                double y = VoiceVisualizer.CY + shape * envelope * (10 + wave * 2.9) * s.gain();
                if (!started) {
                    g.moveTo(x, y);
                    started = true;
                } else {
                    g.lineTo(x, y);
                }
            }
            g.stroke();
        }
    }

    /** Barras verticais nas pontas, como o espectro da referência. */
    private static void bars(GraphicsContext g, VoiceVisualizer.WaveState s, VoiceVisualizer.Palette palette) {
        g.setLineCap(StrokeLineCap.ROUND);
        Random random = new Random(11);
        for (int side = 0; side < 2; side++) {
            for (int bar = 0; bar < 14; bar++) {
                double x = side == 0 ? -4 + bar * 4.2 : VoiceVisualizer.W + 4 - bar * 4.2;
                double band = bar < 5 ? s.bass() : bar < 10 ? s.mid() : s.treble();
                double height = 8 + random.nextDouble() * 24 * (1 - bar / 16.0) + band * 46;
                g.setStroke(palette.col("#1BE4F8", 0.35 + random.nextDouble() * 0.45));
                g.setLineWidth(1.4);
                g.strokeLine(x, VoiceVisualizer.CY - height, x, VoiceVisualizer.CY + height);
            }
        }
        g.setLineCap(StrokeLineCap.BUTT);
    }

    /** O traço central claro só mostra amostras do som que está tocando. */
    private static void sample(GraphicsContext g, VoiceVisualizer.WaveState s, VoiceVisualizer.Palette palette) {
        if (!s.playing()) {
            return;
        }
        for (int glow = 2; glow >= 0; glow--) {
            g.setStroke(palette.col("#21EAFF", glow == 0 ? 0.95 : 0.09));
            g.setLineWidth(glow == 0 ? 1.5 : glow * 4);
            g.beginPath();
            for (int x = 0; x <= (int) VoiceVisualizer.W; x += 2) {
                double y = VoiceVisualizer.CY + s.sample().applyAsDouble(-2048 + x * 2) * 420;
                if (x == 0) {
                    g.moveTo(x, y);
                } else {
                    g.lineTo(x, y);
                }
            }
            g.stroke();
        }
    }
}
