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
package zordon.core.zwp;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.BinaryFrame;
import zordon.zwp.BinaryFrameCodec;
import zordon.zwp.ZwpCodecException;

/** O que a aplicação registrou no servidor: métodos, quem ouve as sessões e quem recebe os frames binários. */
final class ZwpHandlers {

    private static final Logger log = LoggerFactory.getLogger(ZwpServer.class);

    private final MethodDispatch methods = new MethodDispatch();
    private final List<Consumer<ZwpSession>> readyListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ZwpSession>> closedListeners = new CopyOnWriteArrayList<>();
    private volatile BinaryHandler binary;

    MethodDispatch methods() {
        return methods;
    }

    void register(String method, MethodHandler handler) {
        methods.register(method, handler);
    }

    void registerAsync(String method, AsyncMethodHandler handler) {
        methods.registerAsync(method, handler);
    }

    void onReady(Consumer<ZwpSession> listener) {
        readyListeners.add(listener);
    }

    void onClosed(Consumer<ZwpSession> listener) {
        closedListeners.add(listener);
    }

    void onBinary(BinaryHandler handler) {
        this.binary = handler;
    }

    void ready(ZwpSession session) {
        notifyListeners(readyListeners, session);
    }

    void closed(ZwpSession session) {
        notifyListeners(closedListeners, session);
    }

    /** Um frame binário (SPEC-009). Sem quem o receba, ou antes do hello, é descartado. */
    void binary(ZwpSession session, ByteBuffer bytes) {
        BinaryHandler handler = binary;
        if (session == null || !session.helloCompleted() || handler == null) {
            return;
        }
        BinaryFrame frame;
        try {
            frame = BinaryFrameCodec.decode(bytes);
        } catch (ZwpCodecException e) {
            handler.malformed(session, e.getMessage());
            return;
        }
        handler.frame(session, frame);
    }

    private void notifyListeners(List<Consumer<ZwpSession>> listeners, ZwpSession session) {
        for (Consumer<ZwpSession> listener : listeners) {
            try {
                listener.accept(session);
            } catch (RuntimeException e) {
                log.error("falha em listener de sessão", e);
            }
        }
    }
}
