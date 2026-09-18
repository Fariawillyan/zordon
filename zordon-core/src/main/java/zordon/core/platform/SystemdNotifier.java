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
package zordon.core.platform;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.lang.invoke.MethodHandle;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementa {@code sd_notify} para a unit com {@code Type=notify}
 * (docs/operations/install.md §3).
 *
 * <p>O protocolo do systemd usa socket Unix de datagrama, que o JDK não expõe
 * ({@code DatagramChannel} não aceita a família UNIX). A alternativa seria mudar a
 * unit para {@code Type=exec}, mas aí o serviço apareceria ativo antes de o ZWP
 * estar escutando — e é justamente essa a informação que o supervisor precisa.
 *
 * <p>Esta é a única classe do núcleo que chama código nativo. A regra arquitetural
 * que a mantém sozinha está em {@code ArchitectureTest}.
 */
public final class SystemdNotifier {

    private static final Logger log = LoggerFactory.getLogger(SystemdNotifier.class);

    private static final int AF_UNIX = 1;
    private static final int SOCK_DGRAM = 2;
    private static final int SUN_PATH_LENGTH = 108;

    private final Optional<String> socketPath;

    public SystemdNotifier() {
        this(System.getenv("NOTIFY_SOCKET"));
    }

    SystemdNotifier(String socketPath) {
        this.socketPath = Optional.ofNullable(socketPath).filter(path -> !path.isBlank());
    }

    /** Fora do systemd não há a quem notificar, e isso não é erro. */
    public boolean isActive() {
        return socketPath.isPresent();
    }

    /** Anuncia que o núcleo está pronto: ZWP escutando e endpoint publicado. */
    public void ready() {
        send("READY=1");
    }

    public void stopping() {
        send("STOPPING=1");
    }

    public void status(String text) {
        send("STATUS=" + text);
    }

    // A chamada nativa é restrita por design do JDK; a permissão é declarada no
    // lançamento (--enable-native-access) e o escopo, aqui.
    @SuppressWarnings("restricted")
    private void send(String message) {
        if (socketPath.isEmpty()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            MethodHandle socket = linker.downcallHandle(
                    linker.defaultLookup().findOrThrow("socket"),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            MethodHandle sendto = linker.downcallHandle(
                    linker.defaultLookup().findOrThrow("sendto"),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT));
            MethodHandle close = linker.downcallHandle(
                    linker.defaultLookup().findOrThrow("close"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            int descriptor = (int) socket.invokeExact(AF_UNIX, SOCK_DGRAM, 0);
            if (descriptor < 0) {
                log.warn("não foi possível abrir o socket de notificação do systemd");
                return;
            }
            try {
                MemorySegment address = sockaddrUn(arena, socketPath.orElseThrow());
                byte[] payload = message.getBytes(StandardCharsets.UTF_8);
                MemorySegment buffer = arena.allocateFrom(ValueLayout.JAVA_BYTE, payload);
                long sent = (long) sendto.invokeExact(
                        descriptor, buffer, (long) payload.length, 0, address, (int) address.byteSize());
                if (sent < 0) {
                    log.warn("systemd não recebeu a notificação '{}'", message);
                }
            } finally {
                int ignored = (int) close.invokeExact(descriptor);
            }
        } catch (Throwable e) {
            // Notificar o supervisor nunca pode derrubar o núcleo.
            log.warn("falha ao notificar o systemd: {}", e.toString());
        }
    }

    /**
     * Monta {@code struct sockaddr_un}. Um caminho iniciado por {@code @} é um socket
     * do namespace abstrato do Linux, cujo primeiro byte precisa ser zero.
     */
    private MemorySegment sockaddrUn(Arena arena, String path) {
        MemorySegment address = arena.allocate(2 + SUN_PATH_LENGTH);
        address.set(ValueLayout.JAVA_SHORT, 0, (short) AF_UNIX);
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= SUN_PATH_LENGTH) {
            throw new IllegalStateException("NOTIFY_SOCKET longo demais: " + path);
        }
        for (int i = 0; i < bytes.length; i++) {
            byte value = bytes[i];
            address.set(ValueLayout.JAVA_BYTE, 2L + i, i == 0 && value == '@' ? 0 : value);
        }
        return address;
    }
}
