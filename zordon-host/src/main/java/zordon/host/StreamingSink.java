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

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;
import zordon.api.zwp.ZwpProtocol;

/**
 * Envia os frames ao núcleo como {@code AUDIO_IN}, dentro do crédito (SPEC-009
 * §3). Sem crédito, descarta o frame novo: áudio velho não tem valor, latência
 * tem (ZWP §7).
 */
@Spec("SPEC-009")
public final class StreamingSink implements FrameSink {

    private static final Logger log = LoggerFactory.getLogger(StreamingSink.class);

    private final Predicate<BinaryFrame> transport;

    // Protegidos por this.
    private int creditLimit = ZwpProtocol.DEFAULT_AUDIO_CREDIT_FRAMES;
    private int stream = -1;
    private int available;
    private long seq;
    private long sent;
    private long dropped;

    /** @param transport envia um frame; falso se não conseguiu (offline). */
    public StreamingSink(Predicate<BinaryFrame> transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** O crédito que o núcleo anunciou no hello. */
    public synchronized void creditLimit(int frames) {
        creditLimit = Math.max(1, frames);
    }

    /** Começa o stream anunciado pelo núcleo, com o crédito cheio. */
    public synchronized void begin(int streamId) {
        stream = streamId;
        available = creditLimit;
        seq = 0;
        log.debug("stream de áudio {} iniciado com crédito {}", streamId, creditLimit);
    }

    /** O núcleo consumiu {@code frames} do stream: dá para enviar outros tantos. */
    public synchronized void grant(int streamId, int frames) {
        if (streamId == stream && frames > 0) {
            available = Math.min(creditLimit, available + frames);
        }
    }

    /** Fim normal: avisa o núcleo com {@code AUDIO_END}. */
    public synchronized void end() {
        if (stream < 0) {
            return;
        }
        transport.test(BinaryFrame.end(stream, next()));
        log.debug("stream de áudio {} encerrado: {} enviados, {} descartados sem crédito", stream, sent, dropped);
        stream = -1;
    }

    /** Fim sem aviso: a conexão já caiu, não há a quem avisar. */
    public synchronized void abort() {
        stream = -1;
    }

    @Override
    public synchronized void accept(byte[] frame, int length) {
        if (stream < 0) {
            return;
        }
        if (available == 0) {
            dropped++;
            return;
        }
        if (transport.test(new BinaryFrame(FrameType.AUDIO_IN, stream, next(), Arrays.copyOf(frame, length)))) {
            available--;
            sent++;
        } else {
            dropped++;
        }
    }

    public synchronized long sent() {
        return sent;
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized int available() {
        return available;
    }

    private long next() {
        long current = seq;
        seq = (seq + 1) & BinaryFrame.MAX_SEQ;
        return current;
    }
}
