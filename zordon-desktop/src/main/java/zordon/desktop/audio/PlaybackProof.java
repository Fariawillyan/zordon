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

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A prova de que o som tocou (SPEC-008 v2): a posição do dispositivo chegou ao
 * fim, e no relógio da placa. Sem prova, o resultado é falha com o motivo.
 */
final class PlaybackProof {

    private static final Logger log = LoggerFactory.getLogger(SoundPlayer.class);

    private PlaybackProof() {}

    /** Só com o áudio escoado dá para saber se ele saiu de fato. */
    static SoundPlayer.Outcome judge(SoundPlayer.Output line, SoundSynthesizer.Sound rendered, double elapsed) {
        long position = line.framePosition();
        String error;
        if (position < rendered.frames() - SoundPlayer.POSITION_TOLERANCE) {
            error = SoundPlayer.NOT_REPRODUCED;
        } else if (elapsed < rendered.seconds() * SoundPlayer.REAL_TIME_FRACTION) {
            error = SoundPlayer.NOT_REAL_TIME;
        } else {
            log.info("efeito sonoro tocou em {}: {} frames, {} s", line.name(), rendered.frames(),
                    String.format(Locale.ROOT, "%.2f", elapsed));
            return new SoundPlayer.Played(line.name(), rendered.frames(), rendered.seconds());
        }
        log.warn("efeito sonoro não comprovado em {}: posição {} de {} frames em {} s", line.name(), position,
                rendered.frames(), String.format(Locale.ROOT, "%.2f", elapsed));
        return new SoundPlayer.Failed(error);
    }

    /** A saída nem abriu, ou falhou no meio: o motivo que o usuário lê. */
    static SoundPlayer.Failed unavailable(Exception failure) {
        log.warn("efeito sonoro não tocou: {}", failure.toString());
        return new SoundPlayer.Failed(
                SoundPlayer.inWsl() && SoundPlayer.outputName().isEmpty()
                        ? SoundPlayer.NO_OUTPUT_IN_WSL : SoundPlayer.OUTPUT_UNAVAILABLE);
    }
}
