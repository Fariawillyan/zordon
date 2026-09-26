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
package zordon.core.voice;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O que o usuário pediu à voz: o modo (gravado), o dispositivo preferido, a janela
 * do modo {@code open} e o teste do microfone (SPEC-009). Acessado só sob o lock
 * do {@link VoiceService}.
 */
final class VoiceChoices {

    static final double SILENT_PEAK_DBFS = -60;
    static final double LOW_AVERAGE_DBFS = -45;

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final VoiceStore store;
    private final AudioIngest ingest;
    private final Clock clock;
    /**
     * Relógio monotônico dos prazos. O relógio de parede do WSL salta a cada
     * ~30 s, inclusive para trás (R7); um prazo medido nele pode nunca vencer.
     */
    private final LongSupplier ticker;

    private VoiceMode persisted;
    private String deviceId;
    private Instant openUntil;
    private long openDeadline;
    /** Fim do teste do microfone em andamento (SPEC-009); {@code null} sem teste. */
    private Instant testUntil;
    private long testDeadline;
    private Map<String, Object> lastTest;

    VoiceChoices(VoiceStore store, AudioIngest ingest, Clock clock, LongSupplier ticker) {
        this.store = store;
        this.ingest = ingest;
        this.clock = clock;
        this.ticker = ticker;
        VoiceStore.Saved saved = store.load();
        this.persisted = saved.mode();
        this.deviceId = saved.deviceId();
    }

    /** @return se abriu a janela do modo {@code open}, cujo prazo precisa ser conferido */
    boolean choose(VoiceMode mode) {
        if (mode == VoiceMode.OPEN) {
            openUntil = clock.instant().plus(VoiceService.OPEN_DURATION);
            openDeadline = ticker.getAsLong() + VoiceService.OPEN_DURATION.toNanos();
            return true;
        }
        openUntil = null;
        persisted = mode;
        store.save(new VoiceStore.Saved(persisted, deviceId));
        return false;
    }

    void device(String id) {
        deviceId = id;
        store.save(new VoiceStore.Saved(persisted, deviceId));
    }

    String deviceId() {
        return deviceId;
    }

    VoiceMode desired() {
        return openUntil != null ? VoiceMode.OPEN : persisted;
    }

    Instant openUntil() {
        return openUntil;
    }

    boolean expireOpenIfDue() {
        if (openUntil != null && ticker.getAsLong() - openDeadline >= 0) {
            log.info("modo open venceu; voz volta a {}", persisted.wire());
            openUntil = null;
            return true;
        }
        return false;
    }

    void startTest(int seconds) {
        if (testUntil == null) {
            ingest.resetStats();
        }
        testUntil = clock.instant().plusSeconds(seconds);
        testDeadline = ticker.getAsLong() + TimeUnit.SECONDS.toNanos(seconds);
        ingest.levels(true);
    }

    boolean testing() {
        return testUntil != null;
    }

    Instant testUntil() {
        return testUntil;
    }

    Map<String, Object> lastTest() {
        return lastTest;
    }

    boolean expireTestIfDue() {
        if (testUntil != null && ticker.getAsLong() - testDeadline >= 0) {
            finishTest(null);
            return true;
        }
        return false;
    }

    /** Encerra o teste e guarda o resultado; {@code failure} quando ele não pôde medir. */
    void finishTest(String failure) {
        testUntil = null;
        ingest.levels(false);
        AudioIngest.Stats stats = ingest.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        if (failure == null && stats.frames() == 0) {
            failure = "nenhum áudio chegou do host";
        }
        if (failure != null) {
            result.put("verdict", "failed");
            result.put("message", failure);
        } else {
            result.put("peakDbfs", stats.peakDbfs());
            result.put("averageDbfs", stats.averageDbfs());
            if (stats.peakDbfs() < SILENT_PEAK_DBFS) {
                result.put("verdict", "silent");
                result.put("message", "nenhum sinal: microfone mudo ou errado");
            } else if (stats.averageDbfs() < LOW_AVERAGE_DBFS) {
                result.put("verdict", "low");
                result.put("message", "sinal baixo: aproxime-se ou aumente o ganho");
            } else {
                result.put("verdict", "ok");
                result.put("message", "sinal bom");
            }
        }
        result.put("frames", stats.frames());
        lastTest = result;
        log.info("teste do microfone: {} ({} frames)", result.get("verdict"), stats.frames());
    }

    /** Nanossegundos até o prazo mais próximo, do teste ou do modo {@code open}; {@code MAX_VALUE} sem prazo. */
    long nextDeadline(long now) {
        long next = Long.MAX_VALUE;
        if (testUntil != null) {
            next = Math.min(next, testDeadline - now);
        }
        if (openUntil != null) {
            next = Math.min(next, openDeadline - now);
        }
        return next;
    }
}
