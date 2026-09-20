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
import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import zordon.api.trace.Spec;
import zordon.desktop.audio.SoundPlayer;

/**
 * O console da imagem de referência (SPEC-010), desenhado em Canvas. A cena
 * fica parada em repouso e só se mexe com um som de feedback tocando; ela nunca
 * representa o microfone.
 *
 * <p>Tudo é desenhado num espaço virtual de {@value #W} × {@value #H}, a moldura
 * da imagem (820 × 530) em escala, e ajustado à área disponível sem distorcer.
 */
@Spec("SPEC-008")
final class VoiceVisualizer extends Region {

    static final double W = 1000;
    static final double H = 646;
    /** Centro da esfera, medido na referência. */
    static final double CX = 482;
    static final double CY = 323;
    /** Área dos botões no alto à direita, em coordenadas virtuais. */
    static final double BUTTONS_RIGHT = 24;
    static final double BUTTONS_TOP = 29;
    /** "NÍVEL RMS" e o medidor, abaixo dos botões. */
    static final double METER_X = 804;
    static final double METER_Y = 120;

    private static final Color CYAN = Color.web("#12E3F7");
    private static final Color FRAME = Color.web("#1C6272", 0.75);
    private static final Color TEXT = Color.web("#9FB6C6");

    private final Canvas canvas = new Canvas();
    private final SoundPlayer player;
    private double phase;
    private boolean reducedMotion;
    /** Estado visual do núcleo (SPEC-012): só animação, nenhum texto. */
    private String activity = "idle";
    private long activitySince = System.nanoTime();
    private double micEnergy;
    /** Tempo de animação do estado atual, em segundos; parado em repouso e com movimento reduzido. */
    private double t;
    private Color tintColor;
    private final javafx.animation.AnimationTimer motion = new javafx.animation.AnimationTimer() {
        private long last;

        @Override
        public void handle(long now) {
            if (now - last < 33_000_000L) {
                return;
            }
            last = now;
            draw();
        }
    };

    VoiceVisualizer(SoundPlayer player) {
        this.player = player;
        setMinSize(0, 0);
        setAccessibleText("Console do Zordon. Visualização do som de feedback local; não mede o microfone.");
        getChildren().add(canvas);
        setMouseTransparent(true);
    }

    @Override protected double computePrefWidth(double height) {
        return W;
    }

    @Override protected double computePrefHeight(double width) {
        return H;
    }

    @Override protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    /** Onde a moldura foi desenhada: x, y, largura e altura na área deste nó. */
    Rectangle2D frameBounds() {
        double scale = scale();
        double width = W * scale;
        double height = H * scale;
        return new Rectangle2D((getWidth() - width) / 2, (getHeight() - height) / 2, width, height);
    }

    double scale() {
        double width = getWidth();
        double height = getHeight();
        if (width <= 0 || height <= 0) {
            return 1;
        }
        // Margem da referência: a moldura ocupa ~92% da largura e ~83% da altura da área.
        return Math.min(width * 0.955 / W, height * 0.84 / H);
    }

    void reducedMotion(boolean value) {
        reducedMotion = value;
        animate();
        draw();
    }

    /** Muda o estado visual; a animação roda enquanto ele não for o repouso. */
    void activity(String state) {
        String next = state == null ? "idle" : state;
        if (!next.equals(activity)) {
            activity = next;
            activitySince = System.nanoTime();
        }
        animate();
        draw();
    }

    String activity() {
        return activity;
    }

    /** Nível do microfone em dBFS, para o anel respirar enquanto ouve. */
    void micLevel(double dbfs) {
        micEnergy = Math.max(0, Math.min(1, (dbfs + 60) / 45));
    }

    private void animate() {
        if (!"idle".equals(activity) && !reducedMotion) {
            motion.start();
        } else {
            motion.stop();
        }
    }

    /** A cor do estado: âmbar em atenção, vermelho em erro, verde no instante do concluído. */
    static Color tintFor(String state) {
        return switch (state) {
            case "attention" -> Color.web("#F0B341");
            case "error" -> Color.web("#FF5B6B");
            case "done" -> Color.web("#35D48A");
            default -> null;
        };
    }

    /** Uma cor do desenho, puxada para a cor do estado quando ele tem uma. */
    private Color col(String hex, double alpha) {
        Color base = Color.web(hex, alpha);
        return tintColor == null ? base
                : base.interpolate(Color.color(tintColor.getRed(), tintColor.getGreen(), tintColor.getBlue(), alpha), 0.75);
    }

    void tick() {
        phase = player.playing() && !reducedMotion ? player.frame() / 44100.0 : 0;
        draw();
    }

    private void draw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        t = reducedMotion || "idle".equals(activity) ? 0 : (System.nanoTime() - activitySince) / 1e9;
        tintColor = tintFor(activity);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#06121A"));
        g.fillRect(0, 0, width, height);
        backgroundGrid(g, width, height);
        Rectangle2D frame = frameBounds();
        g.save();
        g.translate(frame.getMinX(), frame.getMinY());
        g.scale(scale(), scale());
        g.beginPath();
        g.rect(0, 0, W, H);
        g.clip();
        g.setFill(new RadialGradient(0, 0, CX / W, CY / H, 0.62, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0A2F3C")), new Stop(0.45, Color.web("#08202B")),
                new Stop(1, Color.web("#061319"))));
        g.fillRect(0, 0, W, H);
        stars(g);
        horizonArcs(g);
        waves(g);
        orb(g);
        g.restore();
        g.save();
        g.translate(frame.getMinX(), frame.getMinY());
        g.scale(scale(), scale());
        frame(g);
        texts(g);
        g.restore();
    }

    /** A grade quase invisível do fundo da janela, fora da moldura. */
    private static void backgroundGrid(GraphicsContext g, double width, double height) {
        g.setStroke(Color.web("#12303D", 0.35));
        g.setLineWidth(0.5);
        for (double x = 0; x < width; x += 28) {
            g.strokeLine(x, 0, x, height);
        }
        for (double y = 0; y < height; y += 28) {
            g.strokeLine(0, y, width, y);
        }
    }

    private static void stars(GraphicsContext g) {
        Random random = new Random(73);
        for (int i = 0; i < 260; i++) {
            double x = random.nextDouble() * W;
            double y = random.nextDouble() * H;
            double size = random.nextDouble() < 0.9 ? 1.1 : 2;
            g.setFill(Color.web("#2FD9F0", 0.12 + random.nextDouble() * 0.45));
            g.fillOval(x, y, size, size);
        }
    }

    /** Arcos largos e tênues na parte de baixo, como o chão da referência. */
    private static void horizonArcs(GraphicsContext g) {
        g.setLineWidth(0.8);
        for (int i = 0; i < 3; i++) {
            double r = 520 + i * 70;
            g.setStroke(Color.web("#1B8BA3", 0.16 - i * 0.04));
            g.strokeArc(CX - r, CY - r + 40, r * 2, r * 2, 200, 140, ArcType.OPEN);
        }
    }

    private void waves(GraphicsContext g) {
        // Fios das ondas laterais: finos, somem perto da esfera e nas pontas.
        for (int wave = 0; wave < 26; wave++) {
            g.setStroke(col("#12DDF2", 0.14 + (wave % 5) * 0.05));
            g.setLineWidth(0.8);
            g.beginPath();
            boolean started = false;
            for (int x = 0; x <= (int) W; x += 3) {
                double distance = Math.abs(x - CX);
                if (distance < 150) {
                    started = false;
                    continue;
                }
                double side = (distance - 150) / (CX - 150);
                double envelope = Math.pow(Math.sin(Math.min(1, side) * Math.PI), 1.2);
                double y = CY + Math.sin(x * 0.03 + wave * 0.24 + phase * 2 + wavePhase()) * envelope
                        * (10 + wave * 2.9) * waveGain();
                if (!started) {
                    g.moveTo(x, y);
                    started = true;
                } else {
                    g.lineTo(x, y);
                }
            }
            g.stroke();
        }
        // Barras verticais nas pontas, como o espectro da referência.
        g.setLineCap(StrokeLineCap.ROUND);
        Random random = new Random(11);
        for (int side = 0; side < 2; side++) {
            for (int bar = 0; bar < 14; bar++) {
                double x = side == 0 ? -4 + bar * 4.2 : W + 4 - bar * 4.2;
                double height = 8 + random.nextDouble() * 34 * (1 - bar / 16.0);
                g.setStroke(col("#1BE4F8", 0.35 + random.nextDouble() * 0.45));
                g.setLineWidth(1.4);
                g.strokeLine(x, CY - height, x, CY + height);
            }
        }
        g.setLineCap(StrokeLineCap.BUTT);
        // O traço central claro só mostra amostras do som que está tocando.
        if (player.level() > 0 && !reducedMotion) {
            for (int glow = 2; glow >= 0; glow--) {
                g.setStroke(col("#21EAFF", glow == 0 ? 0.95 : 0.09));
                g.setLineWidth(glow == 0 ? 1.5 : glow * 4);
                g.beginPath();
                for (int x = 0; x <= (int) W; x += 2) {
                    double y = CY + player.sample(-2048 + x * 2) * 420;
                    if (x == 0) {
                        g.moveTo(x, y);
                    } else {
                        g.lineTo(x, y);
                    }
                }
                g.stroke();
            }
        }
        g.setStroke(col("#1CEBFF", 0.55));
        g.setLineWidth(1);
        g.strokeLine(0, CY, CX - 150, CY);
        g.strokeLine(CX + 150, CY, W, CY);
        // Marcadores curtos dos dois lados da esfera.
        g.setStroke(col("#12E3F7", 1));
        g.setLineWidth(3.2);
        g.strokeLine(CX - 229, CY - 22, CX - 229, CY + 22);
        g.strokeLine(CX + 231, CY - 22, CX + 231, CY + 22);
    }

    private void orb(GraphicsContext g) {
        double energy = reducedMotion ? 0 : Math.min(1, player.level() * 5);
        if ("listening".equals(activity) && !reducedMotion) {
            energy = Math.max(energy, micEnergy);
        }
        double radius = 150 + energy * 8;
        g.setFill(new RadialGradient(0, 0, CX, CY, 260, false, CycleMethod.NO_CYCLE,
                new Stop(0, col("#00D9EF", 0.05)), new Stop(0.6, col("#00D9EF", 0.04)),
                new Stop(0.7, col("#00DBFF", 0.10)), new Stop(1, Color.TRANSPARENT)));
        g.fillOval(CX - 260, CY - 260, 520, 520);

        // Anéis concêntricos; o último pontilhado.
        double[] rings = {176, 196, 222, 250, 268};
        for (int i = 0; i < rings.length; i++) {
            double r = rings[i];
            g.setStroke(col("#10AFCF", i == 1 ? 0.38 : 0.2));
            g.setLineWidth(i == 1 ? 1.1 : 0.7);
            if (i == rings.length - 1) {
                g.setLineDashes(1.5, 6);
            }
            g.strokeOval(CX - r, CY - r, r * 2, r * 2);
            g.setLineDashes();
        }
        // Arco grosso no alto e no pé, e as marcas hachuradas nas diagonais.
        g.setStroke(col("#12E3F7", 1));
        g.setLineWidth(5);
        g.strokeArc(CX - 236, CY - 236, 472, 472, 78 + phase * 6 + arcSpin(), 22, ArcType.OPEN);
        g.setLineWidth(1.4);
        g.setStroke(col("#12E3F7", 0.5));
        g.strokeArc(CX - 236, CY - 236, 472, 472, 60 + phase * 6 + arcSpin(), 16, ArcType.OPEN);
        g.setStroke(col("#12E3F7", 0.7));
        g.setLineWidth(2);
        g.strokeArc(CX - 236, CY - 236, 472, 472, 258 + arcSpin(), 24, ArcType.OPEN);
        double sweep = "executing".equals(activity) ? t * 60 : 0;
        hatch(g, 128 + sweep, 146 + sweep, 212, col("#12E3F7", 0.75));
        hatch(g, 308 + sweep, 328 + sweep, 212, col("#12E3F7", 0.75));
        satellites(g);

        for (int glow = 10; glow >= 1; glow--) {
            g.setLineWidth(glow * 2.6);
            g.setStroke(col("#00DBFF", 0.022 + (11 - glow) * 0.006));
            g.strokeOval(CX - radius, CY - radius, radius * 2, radius * 2);
        }
        Random random = new Random(19);
        for (int i = 0; i < 2400; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double z = random.nextDouble() * 2 - 1;
            double orbit = radius * Math.sqrt(1 - z * z);
            double rotation = angle + phase * 0.11 + particleSpin();
            double x = orbit * Math.cos(rotation);
            double y = radius * z;
            double depth = Math.sin(rotation);
            g.setFill(col("#27EAFF", 0.08 + 0.38 * Math.abs(depth)));
            double pull = "understanding".equals(activity) ? 0.86 + 0.14 * Math.cos(t * 3) : 1;
            g.fillOval(CX + x * pull, CY + y * pull, depth > 0.5 ? 1.4 : 0.8, depth > 0.5 ? 1.4 : 0.8);
        }
        for (int i = 0; i < 1700; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = radius + random.nextGaussian() * 4.2;
            double size = 0.5 + random.nextDouble() * 1.6;
            g.setFill(col("#50EDFF", 0.15 + random.nextDouble() * 0.55));
            g.fillOval(CX + Math.cos(angle) * r, CY + Math.sin(angle) * r, size, size);
        }
        g.setFill(new RadialGradient(0, 0, CX, CY, radius * 0.86, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#06151E", 0.99)), new Stop(0.7, Color.web("#06151E", 0.93)),
                new Stop(1, Color.TRANSPARENT)));
        g.fillOval(CX - radius * 0.86, CY - radius * 0.86, radius * 1.72, radius * 1.72);
        g.setLineWidth(1.8);
        g.setStroke(col("#7DF6FF", 0.95 * pulse()));
        g.strokeOval(CX - radius, CY - radius, radius * 2, radius * 2);
        if ("done".equals(activity) && t < 1.2) {
            double r = radius + (t / 1.2) * 130;
            g.setLineWidth(2.5);
            g.setStroke(col("#7DF6FF", 0.8 * (1 - t / 1.2)));
            g.strokeOval(CX - r, CY - r, r * 2, r * 2);
        }
        for (int i = 0; i < 2; i++) {
            double y = CY + (i == 0 ? -radius : radius);
            g.setFill(new RadialGradient(0, 0, CX, y, 34, false, CycleMethod.NO_CYCLE,
                    new Stop(0, col("#E0FFFF", 0.9)), new Stop(0.14, col("#00EAFF", 0.55)),
                    new Stop(1, Color.TRANSPARENT)));
            g.fillOval(CX - 34, y - 34, 68, 68);
        }

        // O símbolo: triângulo com o triângulo interno aberto, como na referência.
        g.setStroke(col("#12E3F7", 1));
        g.setLineWidth(4.2);
        g.strokePolygon(new double[] {CX, CX - 42, CX + 42}, new double[] {CY - 78, CY - 6, CY - 6}, 3);
        g.setLineWidth(3);
        g.strokePolyline(new double[] {CX, CX - 20, CX + 20, CX + 4},
                new double[] {CY - 52, CY - 18, CY - 18, CY - 45}, 4);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(col("#12E3F7", 1));
        g.setFont(Font.font("Inter", FontWeight.BOLD, 30));
        g.fillText("Z O R D O N", CX, CY + 40);
        g.setFill(col("#A8E4EE", 1));
        g.setFont(Font.font("Inter", 11));
        g.fillText("O U V I N D O   O   F U T U R O", CX, CY + 64);
    }

    /** Quanto as ondas avançam: falando, ouvindo e executando elas correm. */
    private double wavePhase() {
        return switch (activity) {
            case "speaking" -> t * 4;
            case "listening" -> t * 2;
            case "executing" -> t * 3;
            default -> 0;
        };
    }

    /** Altura das ondas: falando, elas pulsam; ouvindo, seguem o microfone. */
    private double waveGain() {
        return switch (activity) {
            case "speaking" -> 0.7 + 0.5 * Math.sin(t * 6);
            case "listening" -> reducedMotion ? 1 : 0.5 + micEnergy * 1.2;
            default -> 1;
        };
    }

    /** Graus por segundo dos arcos: planejar é devagar, executar é rápido. */
    private double arcSpin() {
        return switch (activity) {
            case "understanding" -> t * 10;
            case "planning" -> t * 20;
            case "executing" -> t * 90;
            case "agents" -> t * 35;
            default -> 0;
        };
    }

    private double particleSpin() {
        return switch (activity) {
            case "understanding" -> t * 1.5;
            case "planning" -> t * 0.3;
            case "executing" -> t * 0.8;
            case "agents" -> t * 0.5;
            default -> 0;
        };
    }

    /** Pulso do anel: lento em atenção, rápido em erro. */
    private double pulse() {
        return switch (activity) {
            case "attention" -> 0.6 + 0.4 * Math.sin(t * 2);
            case "error" -> 0.55 + 0.45 * Math.sin(t * 4);
            default -> 1;
        };
    }

    /** Agentes trabalhando: um satélite em órbita por agente (três até o M5 dizer quantos). */
    private void satellites(GraphicsContext g) {
        if (!"agents".equals(activity)) {
            return;
        }
        double[] speeds = {40, -30, 55};
        double[] radii = {196, 222, 250};
        for (int i = 0; i < speeds.length; i++) {
            double angle = Math.toRadians(i * 120 + t * speeds[i]);
            double x = CX + Math.cos(angle) * radii[i];
            double y = CY - Math.sin(angle) * radii[i];
            g.setFill(new RadialGradient(0, 0, x, y, 12, false, CycleMethod.NO_CYCLE,
                    new Stop(0, col("#E0FFFF", 0.95)), new Stop(1, Color.TRANSPARENT)));
            g.fillOval(x - 12, y - 12, 24, 24);
        }
    }

    /** Traços curtos ao longo de um arco, entre dois ângulos. */
    private static void hatch(GraphicsContext g, double from, double to, double radius, Color color) {
        g.setStroke(color);
        g.setLineWidth(1.6);
        for (double angle = from; angle <= to; angle += 3) {
            double rad = Math.toRadians(angle);
            double cos = Math.cos(rad);
            double sin = -Math.sin(rad);
            g.strokeLine(CX + cos * radius, CY + sin * radius, CX + cos * (radius + 10), CY + sin * (radius + 10));
        }
    }

    private void frame(GraphicsContext g) {
        g.setStroke(FRAME);
        g.setLineWidth(1);
        g.strokeRect(0.5, 0.5, W - 1, H - 1);
        g.setStroke(CYAN);
        g.setLineWidth(2);
        double cut = 16;
        double arm = 32;
        // Cantos com o corte diagonal da referência.
        g.strokePolyline(new double[] {0, 0, cut, cut + arm}, new double[] {cut + arm, cut, 0, 0}, 4);
        g.strokePolyline(new double[] {W, W, W - cut, W - cut - arm}, new double[] {cut + arm, cut, 0, 0}, 4);
        g.strokePolyline(new double[] {0, 0, cut, cut + arm}, new double[] {H - cut - arm, H - cut, H, H}, 4);
        g.strokePolyline(new double[] {W, W, W - cut, W - cut - arm},
                new double[] {H - cut - arm, H - cut, H, H}, 4);
        g.setLineWidth(1.6);
        g.strokeLine(0, 380, 0, 398);
        g.strokeLine(W, 392, W, 410);
        // Linhas verticais pontilhadas no eixo da esfera, no alto e no pé.
        g.setStroke(Color.web("#12E3F7", 0.55));
        g.setLineWidth(1);
        g.strokeLine(CX, 12, CX, 78);
        g.strokeLine(CX, 580, CX, 606);
        g.setFill(CYAN);
        for (double y : new double[] {12, 30, 48, 606}) {
            g.fillOval(CX - 2, y - 2, 4, 4);
        }
    }

    private void texts(GraphicsContext g) {
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(TEXT);
        g.setFont(Font.font("Inter", 13));
        g.fillText("E F E I T O S   S O N O R O S", 35, 55);
        g.setFill(Color.web("#4FD8EF"));
        g.setFont(Font.font("JetBrains Mono", 11.5));
        g.fillText("44 100 Hz  /  ESTÉREO", 35, 78);

        g.setFill(TEXT);
        g.setFont(Font.font("Inter", 10));
        g.fillText("N Í V E L   R M S", METER_X, METER_Y);
        // Em repouso as barras ficam apagadas: acesas sem som seria um nível inventado.
        int lit = (int) Math.round(Math.min(1, player.level() * 4) * 24);
        for (int i = 0; i < 24; i++) {
            g.setFill(i < lit ? CYAN : Color.web("#12E3F7", 0.24));
            g.fillRect(METER_X + i * 6.1, METER_Y + 12, 3, 13);
        }

        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#56DDEF"));
        g.setFont(Font.font("Inter", 11));
        g.fillText("V O Z   Q U E   T R A N S C E N D E", CX, 632);
        g.setStroke(CYAN);
        g.setLineWidth(1);
        g.strokeLine(283, 627, 367, 627);
        g.strokeLine(598, 627, 682, 627);
    }
}
