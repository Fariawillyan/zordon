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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** O áudio de uma fala chegando do motor em pedaços; o fim chega como uma marca na fila. */
final class SynthesizedAudio implements VoiceEngine.Speech {

    private static final Object END = new Object();

    private final BlockingQueue<Object> chunks = new LinkedBlockingQueue<>();
    private volatile int rate;

    @Override
    public void chunk(int sampleRate, byte[] pcm) {
        rate = sampleRate;
        chunks.add(pcm);
    }

    @Override
    public void end(String reason) {
        chunks.add(END);
    }

    int rate() {
        return rate;
    }

    /** O próximo pedaço, ou {@code null} no fim ou depois de 10 s sem nada. */
    byte[] next() throws InterruptedException {
        return chunks.poll(10, TimeUnit.SECONDS) instanceof byte[] pcm ? pcm : null;
    }
}
