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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import zordon.api.trace.Spec;
import zordon.desktop.audio.SoundSynthesizer.Cue;

/**
 * Prova, no computador onde o desktop roda, que os seis sinais saem pela placa
 * (SPEC-008 v2). Toca cada um com volume zero — mesma duração, mesmo caminho de
 * produção, sem incomodar ninguém — e exige a reprodução comprovada.
 *
 * <p>Uso: {@code java -cp <desktop> zordon.desktop.audio.PlaybackCheck}. Código de
 * saída 0 só se os seis sinais forem comprovados.
 */
@Spec("SPEC-008")
public final class PlaybackCheck {

    private PlaybackCheck() {}

    public static void main(String[] args) throws InterruptedException {
        int failures;
        try (SoundPlayer player = new SoundPlayer()) {
            failures = run(player, SoundPlayer.outputName(), System.out);
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /** @return quantos sinais não foram comprovados. */
    static int run(SoundPlayer player, String outputName, PrintStream out) throws InterruptedException {
        out.println("saída padrão: " + (outputName.isEmpty() ? "nenhuma" : outputName));
        player.setVolume(0);
        List<String> failed = new ArrayList<>();
        for (Cue cue : Cue.values()) {
            player.play(cue, SoundSynthesizer.Settings.DEFAULT);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            // O worker marca "tocando" ao começar; espera ele terminar ou desistir.
            do {
                Thread.sleep(20);
            } while (player.playing() && System.nanoTime() < deadline);
            SoundPlayer.Outcome outcome = player.lastOutcome();
            if (outcome instanceof SoundPlayer.Played played) {
                out.printf(java.util.Locale.ROOT, "ok     %-14s %6d frames  %.2f s  em %s%n",
                        cue, played.frames(), played.seconds(), played.output());
            } else {
                failed.add(cue.toString());
                out.printf("FALHOU %-14s %s%n", cue, outcome instanceof SoundPlayer.Failed failure
                        ? failure.reason()
                        : player.playing() ? "não terminou em 10 s" : String.valueOf(outcome));
                player.stop();
            }
        }
        out.println(failed.isEmpty() ? "seis sinais comprovados" : "não comprovados: " + String.join(", ", failed));
        return failed.size();
    }
}
