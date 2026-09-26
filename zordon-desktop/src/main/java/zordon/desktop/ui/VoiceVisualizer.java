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
import java.util.function.IntToDoubleFunction;
import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import zordon.api.trace.Spec;
import zordon.desktop.audio.SoundPlayer;

/**
 * O console da imagem de referência (SPEC-010), desenhado em Canvas. O núcleo
 * recebe métricas do microfone em tempo real e as transforma em movimento,
 * profundidade e luz, sem transportar o áudio bruto para a interface.
 *
 * <p>Tudo é desenhado num espaço virtual de {@value #W} × {@value #H}, a moldura
 * da imagem (820 × 530) em escala, e ajustado à área disponível sem distorcer.
 */
@Spec("SPEC-008")
final class VoiceVisualizer extends Region {

    record ParticleState(double t, double phase, double bass, double mid, double treble, String activity,
            double intensity) {}
    record WaveState(double t, double phase, double bass, double mid, double treble, double wavePhase, double gain,
            boolean playing, IntToDoubleFunction sample) {}

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
    /** O vermelho do OPPRESSOR MODE, o mesmo do tema da janela. */
    private static final Color OPPRESSOR = Color.web("#FF3B4E");
    private static final Color FRAME = Color.web("#1C6272", 0.75);
    private static final Color TEXT = Color.web("#9FB6C6");

    private final Canvas canvas = new Canvas();
    private final SoundPlayer player;
    private double phase;
    private boolean reducedMotion;
    /** Estado visual do núcleo (SPEC-012): só animação, nenhum texto. */
    private String activity = "idle";
    /** OPPRESSOR MODE (SPEC-036): sobrepõe a cor do estado e acelera tudo. */
    private boolean oppressor;
    private long activitySince = System.nanoTime();
    private double micEnergy;
    private double micBass;
    private double micMid;
    private double micTreble;
    /** Tempo de animação do estado atual, em segundos; parado em repouso e com movimento reduzido. */
    private double t;
    private Color tintColor;
    private final double[] starX = new double[180];
    private final double[] starY = new double[180];
    private final double[] starDepth = new double[180];
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
        setAccessibleText("Núcleo audiovisual do Zordon. Reage ao estado da voz e ao espectro do microfone.");
        getChildren().add(canvas);
        setMouseTransparent(true);
        Rectangle boundsClip = new Rectangle();
        boundsClip.widthProperty().bind(widthProperty());
        boundsClip.heightProperty().bind(heightProperty());
        setClip(boundsClip);
        Random random = new Random(73);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = random.nextDouble() * W;
            starY[i] = random.nextDouble() * H;
            starDepth[i] = 0.25 + random.nextDouble() * 0.75;
        }
    }

    @Override protected double computePrefWidth(double height) {
        return W;
    }

    @Override protected double computePrefHeight(double width) {
        return H;
    }

    @Override protected void layoutChildren() {
        double width = Math.max(0, getWidth());
        double height = Math.max(0, getHeight());
        double oldWidth = canvas.getWidth();
        double oldHeight = canvas.getHeight();
        if (oldWidth != width || oldHeight != height) {
            // JavaFX pode preservar a textura antiga quando o Canvas encolhe
            // durante uma troca de estado. Limpa antes de alterar a superfície
            // para que nenhum frame antigo fique "afundando" para a direita.
            canvas.getGraphicsContext2D().clearRect(0, 0, oldWidth, oldHeight);
            canvas.setWidth(width);
            canvas.setHeight(height);
        }
        canvas.resizeRelocate(0, 0, width, height);
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

    /** Muda o estado visual; até o repouso tem uma deriva quase imperceptível. */
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

    /**
     * Liga o OPPRESSOR MODE no desenho (SPEC-036 CA-8).
     *
     * <p>Sobrepõe a cor de qualquer estado: enquanto o modo dura, o que a tela
     * precisa dizer não é "ouvindo" ou "pensando", é que nada está sendo
     * perguntado antes de executar.
     */
    void oppressor(boolean value) {
        oppressor = value;
        draw();
    }

    /** Quanto o OPPRESSOR MODE acelera e amplia o movimento. */
    private double intensity() {
        return oppressor ? 1.8 : 1;
    }

    /**
     * Nível do microfone em dBFS: RMS, pico, graves, médios e agudos.
     *
     * <p>Só guarda o valor: quem desenha é o {@code motion}. Com áudio ativo
     * chegam ~20 níveis por segundo, e desenhar em cada um somava aos 30
     * quadros do próprio timer e aos 30 do {@code VoiceEffectsPane} — ~80
     * quadros por segundo de uma cena com cerca de 13 mil comandos de Canvas,
     * o triplo do que o timer já entrega, sem nada em troca.
     */
    void micLevel(double[] levels) {
        if (levels == null || levels.length == 0) {
            return;
        }
        micEnergy = smooth(micEnergy, normalized(levels[0]));
        if (levels.length > 2) {
            micBass = smooth(micBass, normalized(levels[2]));
        }
        if (levels.length > 3) {
            micMid = smooth(micMid, normalized(levels[3]));
        }
        if (levels.length > 4) {
            micTreble = smooth(micTreble, normalized(levels[4]));
        }
        if (reducedMotion) {
            // Com movimento reduzido o timer está parado, e as barras do espectro
            // congelariam. Aqui não há enxurrada: este é o único desenho.
            draw();
        }
    }

    private static double normalized(double dbfs) {
        return Math.clamp((dbfs + 60) / 60, 0, 1);
    }

    private static double smooth(double current, double target) {
        double factor = target > current ? 0.48 : 0.16;
        return current + (target - current) * factor;
    }

    private void animate() {
        if (!reducedMotion) {
            motion.start();
        } else {
            motion.stop();
        }
    }

    /** A cor do estado: âmbar em atenção, vermelho em erro, verde no instante do concluído. */
    static Color tintFor(String state) {
        return switch (state) {
            case "listening" -> Color.web("#16DFF3");
            case "understanding" -> Color.web("#8A86FF");
            case "planning" -> Color.web("#D6B56A");
            case "speaking" -> Color.web("#B3F8FF");
            case "executing" -> Color.web("#5BE0A4");
            case "agents" -> Color.web("#9F8BFF");
            case "attention" -> Color.web("#F0B341");
            case "error" -> Color.web("#FF5B6B");
            case "done" -> Color.web("#35D48A");
            default -> null;
        };
    }

    /**
     * Uma cor do desenho, puxada para a cor do estado quando ele tem uma.
     *
     * <p>O alfa é limitado aqui porque vários deles somam faixas do microfone e
     * podem passar de 1. {@code Color.web} lança nesse caso, a exceção sobe até
     * o {@code AnimationTimer} e o quadro morre no meio: some tudo o que viria
     * depois — o emblema, a moldura e os textos. Um desenho fora de faixa é um
     * desenho saturado, nunca uma tela pela metade.
     */
    private Color col(String hex, double alpha) {
        double safe = alpha > 0 ? Math.min(1, alpha) : 0;
        Color base = Color.web(hex, safe);
        return tintColor == null ? base
                : base.interpolate(Color.color(tintColor.getRed(), tintColor.getGreen(), tintColor.getBlue(), safe), 0.75);
    }

    /** Avança a fase da forma de onda. Não desenha: ver {@link #micLevel(double[])}. */
    void tick() {
        phase = player.playing() && !reducedMotion ? player.frame() / 44100.0 : 0;
    }

    private void draw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        t = reducedMotion ? 0 : (System.nanoTime() - activitySince) / 1e9;
        tintColor = oppressor ? OPPRESSOR : tintFor(activity);
        GraphicsContext g = canvas.getGraphicsContext2D();
        // O Canvas do JavaFX no Windows pode manter a matriz do frame anterior
        // quando uma animação e um resize ocorrem juntos. A limpeza precisa
        // sempre acontecer em coordenadas físicas da superfície inteira.
        resetGraphicsState(g);
        g.clearRect(0, 0, width, height);
        g.setFill(Color.web("#06121A"));
        g.fillRect(0, 0, width, height);
        backgroundGrid(g, width, height);
        Rectangle2D frame = frameBounds();
        g.save();
        g.translate(frame.getMinX(), frame.getMinY());
        g.scale(scale(), scale());
        // A cena interna vive dentro da moldura. Sem este corte as ondas saem
        // dela: com a voz forte o ganho leva a curva a ~870 px num espaço de
        // 646, e o traço vaza pela janela inteira.
        g.beginPath();
        g.rect(0, 0, W, H);
        g.clip();
        g.setFill(new RadialGradient(0, 0, CX / W, CY / H, 0.62, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0A2F3C")), new Stop(0.45, Color.web("#08202B")),
                new Stop(1, Color.web("#061319"))));
        g.fillRect(0, 0, W, H);
        stars(g);
        scanlines(g);
        horizonArcs(g);
        waves(g);
        orb(g);
        glitch(g);
        g.restore();
        g.save();
        g.translate(frame.getMinX(), frame.getMinY());
        g.scale(scale(), scale());
        frame(g);
        texts(g);
        g.restore();
        resetGraphicsState(g);
    }

    private static void resetGraphicsState(GraphicsContext g) {
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.setGlobalAlpha(1);
        g.setLineDashes();
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

    private void stars(GraphicsContext g) {
        for (int i = 0; i < starX.length; i++) {
            double depth = starDepth[i];
            double x = Math.floor((starX[i] + Math.sin(t * (0.05 + depth * 0.08) + i) * depth * 9 + W) % W);
            double y = Math.floor((starY[i] + Math.cos(t * (0.025 + depth * 0.04) + i) * depth * 4 + H) % H);
            double size = depth > 0.72 ? 1.8 : depth > 0.45 ? 1.2 : 0.8;
            double alpha = 0.10 + depth * 0.32 + (micTreble * depth * 0.18);
            g.setFill(col("#2FD9F0", alpha));
            g.fillOval(x, y, size, size);
        }
    }

    /** Linhas holográficas muito sutis: textura, não uma camada dominante. */
    private void scanlines(GraphicsContext g) {
        // A grade é fixa. Deslocar uma linha por frações de pixel a cada frame
        // provoca cintilação no rasterizador do JavaFX, especialmente no Windows.
        g.setStroke(col("#63EEFF", 0.026));
        g.setLineWidth(0.8);
        for (double y = 4; y < H; y += 8) {
            g.strokeLine(0, y, W, y);
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
        WaveField.draw(g, new WaveState(t, phase, micBass, micMid, micTreble, wavePhase(), waveGain(),
                player.level() > 0 && !reducedMotion, player::sample));
    }

    private void orb(GraphicsContext g) {
        double energy = reducedMotion ? 0 : Math.min(1, Math.max(player.level() * 5, micEnergy));
        if ("listening".equals(activity) && !reducedMotion) {
            energy = Math.max(energy, micEnergy);
        }
        double radius = 150 + energy * 12 + micBass * 4;
        halo(g);
        rings(g);
        arcs(g);
        glow(g, radius);
        ParticleField.draw(g, radius, new ParticleState(t, phase, micBass, micMid, micTreble, activity, intensity()));
        core(g, radius);
        poles(g, radius);
        emblem(g);
    }

    /** O brilho difuso atrás de tudo. */
    private void halo(GraphicsContext g) {
g.setFill(new RadialGradient(0, 0, CX, CY, 260, false, CycleMethod.NO_CYCLE,
                new Stop(0, col("#00D9EF", 0.05)), new Stop(0.6, col("#00D9EF", 0.04)),
                new Stop(0.7, col("#00DBFF", 0.10)), new Stop(1, Color.TRANSPARENT)));
        g.fillOval(CX - 260, CY - 260, 520, 520);
    }

    private void rings(GraphicsContext g) {
        // Anéis concêntricos; o último pontilhado.
        double[] rings = {176, 196, 222, 250, 268};
        for (int i = 0; i < rings.length; i++) {
            double r = rings[i] + (i < 3 ? micBass * (3 - i) * 3 : 0);
            g.setStroke(col("#10AFCF", i == 1 ? 0.38 + micMid * 0.12 : 0.2 + micTreble * 0.08));
            g.setLineWidth(i == 1 ? 1.1 : 0.7);
            if (i == rings.length - 1) {
                g.setLineDashes(1.5, 6);
            }
            g.strokeOval(CX - r, CY - r, r * 2, r * 2);
            g.setLineDashes();
        }
    }

    private void arcs(GraphicsContext g) {
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
    }

    private void glow(GraphicsContext g, double radius) {
        int layers = 10;
        for (int glow = layers; glow >= 1; glow--) {
            g.setLineWidth(glow * 2.6);
            g.setStroke(col("#00DBFF", 0.018 + (layers + 1 - glow) * 0.005
                    + micTreble * 0.012));
            g.strokeOval(CX - radius, CY - radius, radius * 2, radius * 2);
        }
    }

    /** As partículas em órbita e a poeira ao redor: a mesma semente em todo quadro. */
    /** O miolo escuro, a borda que pulsa e a onda de conclusão. */
    private void core(GraphicsContext g, double radius) {
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
    }

    private void poles(GraphicsContext g, double radius) {
        for (int i = 0; i < 2; i++) {
            double y = CY + (i == 0 ? -radius : radius);
            g.setFill(new RadialGradient(0, 0, CX, y, 34, false, CycleMethod.NO_CYCLE,
                    new Stop(0, col("#E0FFFF", 0.9)), new Stop(0.14, col("#00EAFF", 0.55)),
                    new Stop(1, Color.TRANSPARENT)));
            g.fillOval(CX - 34, y - 34, 68, 68);
        }
    }

    private void emblem(GraphicsContext g) {
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
        double reactive = 0.55 + micBass * 1.15 + micMid * 0.65 + micTreble * 0.3;
        return switch (activity) {
            case "speaking" -> reactive + 0.22 * Math.sin(t * 6);
            case "listening" -> reducedMotion ? 1 : reactive * intensity();
            case "understanding" -> 0.75 + micMid * 0.7;
            case "planning" -> 0.7 + micTreble * 0.35;
            case "executing" -> 0.9 + micBass * 0.5;
            default -> (0.72 + micEnergy * 0.25) * intensity();
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
            case "listening" -> t * (0.12 + micMid * 0.8) * intensity();
            case "speaking" -> t * (0.35 + micTreble * 1.6);
            default -> t * 0.04 * intensity();
        };
    }

    /** Pulso do anel, de 0 a 1: lento em atenção, rápido em erro. */
    private double pulse() {
        double value = switch (activity) {
            case "attention" -> 0.6 + 0.4 * Math.sin(t * 2);
            case "error" -> 0.55 + 0.45 * Math.sin(t * 4);
            case "idle" -> 0.82 + 0.05 * Math.sin(t * 0.8);
            // Com a voz forte esta soma chega a 1,18; quem chama multiplica o
            // resultado por um alfa, então o teto precisa valer já aqui.
            default -> 0.86 + Math.min(0.24, micEnergy * 0.35)
                    + 0.08 * Math.sin(t * (3 + micTreble * 8));
        };
        return Math.clamp(value, 0, 1);
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

    /** Pequenos saltos de sincronismo, raros e curtos, como uma interface holográfica real. */
    private void glitch(GraphicsContext g) {
        if (reducedMotion || "idle".equals(activity)) {
            return;
        }
        double pulse = Math.sin(t * 1.73 + 0.4) * Math.sin(t * 4.91);
        if (pulse < 0.985) {
            return;
        }
        double y = 120 + Math.abs(Math.sin(t * 12)) * 390;
        double shift = 3 + micTreble * 9;
        g.setGlobalAlpha(0.16);
        g.setFill(col("#8AFFFF", 0.7));
        g.fillRect(CX - 250 + shift, y, 130 + micMid * 90, 1);
        g.setFill(col("#8B7CFF", 0.48));
        g.fillRect(CX + 90 - shift, y + 2, 70 + micTreble * 60, 1);
        g.setGlobalAlpha(1);
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
        double rms = Math.max(player.level() * 4, micEnergy);
        int lit = (int) Math.round(Math.min(1, rms) * 24);
        for (int i = 0; i < 24; i++) {
            g.setFill(i < lit ? col("#12E3F7", 0.92) : col("#12E3F7", 0.24));
            g.fillRect(METER_X + i * 6.1, METER_Y + 12, 3, 13);
        }
        g.setFont(Font.font("JetBrains Mono", 8));
        g.setFill(col("#63EAF5", 0.58));
        g.fillText("B", METER_X, METER_Y + 39);
        g.fillText("M", METER_X + 58, METER_Y + 39);
        g.fillText("A", METER_X + 116, METER_Y + 39);
        bandMeter(g, METER_X + 10, METER_Y + 36, micBass);
        bandMeter(g, METER_X + 68, METER_Y + 36, micMid);
        bandMeter(g, METER_X + 126, METER_Y + 36, micTreble);

        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web("#56DDEF"));
        g.setFont(Font.font("Inter", 11));
        g.fillText("V O Z   Q U E   T R A N S C E N D E", CX, 632);
        g.setStroke(CYAN);
        g.setLineWidth(1);
        g.strokeLine(283, 627, 367, 627);
        g.strokeLine(598, 627, 682, 627);
    }

    private void bandMeter(GraphicsContext g, double x, double y, double value) {
        g.setFill(col("#12E3F7", 0.18));
        g.fillRect(x, y, 43, 2);
        g.setFill(col("#12E3F7", 0.72));
        g.fillRect(x, y, 43 * Math.min(1, value * 1.7), 2);
    }
}
