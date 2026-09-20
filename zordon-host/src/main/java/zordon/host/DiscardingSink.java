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
package zordon.host;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Conta e descarta. É o destino dos frames até existirem os frames binários ZWP
 * (SPEC-007 §4): o áudio lido não vai a lugar nenhum, nem a disco nem a log.
 */
public final class DiscardingSink implements FrameSink {

    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();

    @Override
    public void accept(byte[] frame, int length) {
        frames.incrementAndGet();
        bytes.addAndGet(length);
    }

    public long frames() {
        return frames.get();
    }

    public long bytes() {
        return bytes.get();
    }
}
