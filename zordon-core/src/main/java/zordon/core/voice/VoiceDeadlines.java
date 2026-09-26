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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Confere os prazos da voz — modo {@code open}, teste do microfone e escuta — e,
 * enquanto algum não tiver vencido, confere de novo. Uma checagem única que
 * chegasse cedo deixaria o microfone ligado até alguém desligá-lo à mão.
 */
final class VoiceDeadlines {

    private final VoiceService service;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier ticker;
    private final VoiceChoices choices;
    private final ClickListening click;

    /** @param scheduler {@code null} nos testes, que avançam o relógio e consultam o estado */
    VoiceDeadlines(VoiceService service, ScheduledExecutorService scheduler, LongSupplier ticker, VoiceChoices choices,
            ClickListening click) {
        this.service = service;
        this.scheduler = scheduler;
        this.ticker = ticker;
        this.choices = choices;
        this.click = click;
    }

    void schedule(long delayNanos) {
        if (scheduler == null) {
            return;
        }
        scheduler.schedule(() -> {
            service.status();
            synchronized (service) {
                long now = ticker.getAsLong();
                long next = Math.min(choices.nextDeadline(now), click.remaining(now));
                if (next != Long.MAX_VALUE) {
                    schedule(Math.max(next, TimeUnit.MILLISECONDS.toNanos(20)));
                }
            }
        }, delayNanos + TimeUnit.MILLISECONDS.toNanos(20), TimeUnit.NANOSECONDS);
    }
}
