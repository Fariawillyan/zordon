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
            new ArrayBlockingQueue<>(1), Thread.ofPlatform().name("voice-feedback").daemon().factory(),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile Output output;
    private volatile SoundSynthesizer.Sound sound;
    private volatile double volume = 0.35;
    private volatile boolean playing;
    private volatile String error = "";
    private long generation;

    public SoundPlayer() { this(JavaSoundOutput::open); }
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
                    Failed failed = PlaybackProof.unavailable(failure);
                    error = failed.reason();
                    outcome = failed;
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
        Outcome judged = PlaybackProof.judge(line, rendered, elapsed);
        if (judged instanceof Failed failed) {
            error = failed.reason();
        }
        outcome = judged;
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
        return JavaSoundOutput.name();
    }

    /** No WSL não há saída de áudio (R6); dizer isso é mais útil que uma falha genérica. */
    static boolean inWsl() {
        return JavaSoundOutput.inWsl();
    }
}
