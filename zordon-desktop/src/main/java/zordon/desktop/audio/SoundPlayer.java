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
package zordon.desktop.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/** One bounded output worker. The audio device's frame cursor drives visualization. */
@Spec("SPEC-008")
public final class SoundPlayer implements AutoCloseable {
    /**
     * O que aconteceu com o último som (SPEC-008 v2). "Tocou" só com prova: a
     * posição do dispositivo chegou ao fim, em tempo real.
     */
    public sealed interface Outcome permits Played, Failed, Stopped {}

    public record Played(String output, int frames, double seconds) implements Outcome {}

    public record Failed(String reason) implements Outcome {}

    public record Stopped() implements Outcome {}

    /** Tolerância da posição final: meio buffer da linha. */
    static final int POSITION_TOLERANCE = 512;
    /** Abaixo disto, a saída engoliu o áudio sem reproduzi-lo no relógio da placa. */
    static final double REAL_TIME_FRACTION = 0.8;
    static final String NOT_REPRODUCED = "A saída aceitou o áudio, mas não o reproduziu.";
    static final String NOT_REAL_TIME = "A saída consumiu o áudio rápido demais para tê-lo tocado.";
    static final String NO_OUTPUT_IN_WSL =
            "Esta janela roda no WSL, que não tem saída de áudio. Abra o Zordon pelo atalho do Windows.";
    static final String OUTPUT_UNAVAILABLE = "Saída de áudio indisponível. Verifique o dispositivo e tente novamente.";

    private static final Logger log = LoggerFactory.getLogger(SoundPlayer.class);

    public interface Output extends AutoCloseable {
        /** Nome da saída, como o sistema a apresenta. */
        default String name() {
            return "saída de áudio";
        }

        void start();
        int write(byte[] data, int offset, int length);
        long framePosition();
        void drain();
        @Override void close();
    }

    @FunctionalInterface
    public interface OutputFactory { Output open() throws Exception; }

    private final OutputFactory factory;
    private final LongSupplier nanos;
    private volatile Outcome outcome;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "voice-feedback");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile Output output;
    private volatile SoundSynthesizer.Sound sound;
    private volatile double volume = 0.35;
    private volatile boolean playing;
    private volatile String error = "";
    private long generation;

    public SoundPlayer() { this(SoundPlayer::openDefault); }
    public SoundPlayer(OutputFactory factory) {
        this(factory, System::nanoTime);
    }

    public SoundPlayer(OutputFactory factory, LongSupplier nanos) {
        this.factory = factory;
        this.nanos = nanos;
        worker.allowCoreThreadTimeOut(true);
    }

    public synchronized void play(SoundSynthesizer.Cue cue, SoundSynthesizer.Settings settings) {
        stop();
        long ticket = generation;
        error = "";
        playing = true;
        worker.execute(() -> reproduce(ticket, cue, settings));
    }

    private void reproduce(long ticket, SoundSynthesizer.Cue cue, SoundSynthesizer.Settings settings) {
        Output line = null;
        try {
            SoundSynthesizer.Sound rendered = SoundSynthesizer.render(cue, settings);
            synchronized (this) { if (ticket != generation) return; }
            line = factory.open();
            long started;
            synchronized (this) {
                if (ticket != generation) return;
                sound = rendered;
                output = line;
                line.start();
                started = nanos.getAsLong();
            }
            byte[] bytes = new byte[512 * 4];
            for (int frame = 0; frame < rendered.frames();) {
                synchronized (this) { if (ticket != generation) return; }
                int count = Math.min(512, rendered.frames() - frame);
                rendered.pcm(bytes, frame, count, volume);
                int written = 0;
                while (written < count * 4) {
                    synchronized (this) { if (ticket != generation) return; }
                    int next = line.write(bytes, written, count * 4 - written);
                    if (next <= 0) throw new IllegalStateException("Saída não aceitou áudio");
                    written += next;
                }
                frame += count;
            }
            line.drain();
            confirm(ticket, line, rendered, (nanos.getAsLong() - started) / 1e9);
        } catch (Exception failure) {
            synchronized (this) {
                if (ticket == generation) {
                    error = unavailableMessage();
                    outcome = new Failed(error);
                    log.warn("efeito sonoro não tocou: {}", failure.toString());
                }
            }
        } finally {
            if (line != null) line.close();
            synchronized (this) {
                if (ticket == generation) {
                    output = null;
                    sound = null;
                    playing = false;
                }
            }
        }
    }

    /** Só agora, com o áudio escoado, dá para saber se ele saiu de fato. */
    private synchronized void confirm(long ticket, Output line, SoundSynthesizer.Sound rendered, double elapsed) {
        if (ticket != generation) {
            return;
        }
        long position = line.framePosition();
        if (position < rendered.frames() - POSITION_TOLERANCE) {
            error = NOT_REPRODUCED;
        } else if (elapsed < rendered.seconds() * REAL_TIME_FRACTION) {
            error = NOT_REAL_TIME;
        } else {
            outcome = new Played(line.name(), rendered.frames(), rendered.seconds());
            log.info("efeito sonoro tocou em {}: {} frames, {} s", line.name(), rendered.frames(),
                    String.format(java.util.Locale.ROOT, "%.2f", elapsed));
            return;
        }
        outcome = new Failed(error);
        log.warn("efeito sonoro não comprovado em {}: posição {} de {} frames em {} s", line.name(), position,
                rendered.frames(), String.format(java.util.Locale.ROOT, "%.2f", elapsed));
    }

    public synchronized void stop() {
        if (playing) {
            outcome = new Stopped();
        }
        generation++;
        worker.getQueue().clear();
        Output current = output;
        output = null;
        sound = null;
        playing = false;
        if (current != null) current.close();
    }

    public void setVolume(double value) { volume = SoundSynthesizer.gain(value); }
    public double volume() { return volume; }
    public boolean playing() { return playing; }
    public String error() { return error; }
    /** Resultado do último som; {@code null} antes do primeiro. */
    public Outcome lastOutcome() { return outcome; }
    public int frame() {
        Output current = output;
        return current == null ? 0 : (int) Math.min(Integer.MAX_VALUE, current.framePosition());
    }
    public double level() {
        SoundSynthesizer.Sound current = sound;
        return current == null ? 0 : current.rms(frame(), volume);
    }
    public double sample(int offset) {
        SoundSynthesizer.Sound current = sound;
        return current == null ? 0 : current.sample(frame() + offset, volume);
    }

    @Override public synchronized void close() { stop(); worker.shutdownNow(); }

    /** Nome da saída que o sistema usa por padrão, ou vazio se não houver nenhuma. */
    public static String outputName() {
        DataLine.Info wanted = new DataLine.Info(SourceDataLine.class, format());
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(wanted)) {
                return info.getName();
            }
        }
        return "";
    }

    /** No WSL não há saída de áudio (R6); dizer isso é mais útil que uma falha genérica. */
    static boolean inWsl() {
        return System.getenv("WSL_DISTRO_NAME") != null || System.getenv("WSL_INTEROP") != null;
    }

    private static String unavailableMessage() {
        return inWsl() && outputName().isEmpty() ? NO_OUTPUT_IN_WSL : OUTPUT_UNAVAILABLE;
    }

    private static AudioFormat format() {
        return new AudioFormat(SoundSynthesizer.SAMPLE_RATE, 16, 2, true, false);
    }

    private static Output openDefault() throws Exception {
        AudioFormat format = format();
        String name = outputName();
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        try {
            line.open(format, 4096);
        } catch (Exception failure) {
            line.close();
            throw failure;
        }
        return new Output() {
            @Override public String name() { return name.isEmpty() ? "saída padrão" : name; }
            @Override public void start() { line.start(); }
            @Override public int write(byte[] data, int offset, int length) { return line.write(data, offset, length); }
            @Override public long framePosition() { return line.getLongFramePosition(); }
            @Override public void drain() { line.drain(); }
            @Override public void close() { line.stop(); line.flush(); line.close(); }
        };
    }
}
