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
package zordon.core.endpoint;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.EndpointFile;
import zordon.api.zwp.ProtocolRange;
import zordon.api.zwp.ZwpProtocol;
import zordon.core.ZordonConfig;
import zordon.zwp.EndpointFileStore;
import zordon.api.trace.Spec;

/**
 * Publica o {@code endpoint.json} que torna o núcleo descobrível e autenticável
 * (ADR-0006).
 *
 * <p>O token é novo a cada inicialização: um segredo de curta duração por
 * construção, que não precisa de gerenciamento nem de rotação.
 */
@Spec("SPEC-002")
public final class EndpointPublisher {

    private static final Logger log = LoggerFactory.getLogger(EndpointPublisher.class);
    private static final int TOKEN_BYTES = 32;
    private static final Path ROUTE_TABLE = Path.of("/proc/net/route");

    private final EndpointFileStore store = new EndpointFileStore();
    private final ZordonConfig config;

    public EndpointPublisher(ZordonConfig config) {
        this.config = config;
    }

    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Escreve o arquivo no lado WSL e, quando o perfil do Windows é conhecido, também
     * lá.
     *
     * @return o conteúdo publicado
     */
    public EndpointFile publish(String startId, Instant startedAt, String token, int listeningPort) {
        EndpointFile endpoint = new EndpointFile(
                EndpointFile.CURRENT_VERSION,
                startId,
                startedAt,
                config.networkingMode().name().toLowerCase(java.util.Locale.ROOT),
                candidateEndpoints(listeningPort),
                token,
                ProtocolRange.exactly(ZwpProtocol.VERSION),
                ProcessHandle.current().pid());

        store.write(config.endpointFile(), endpoint);
        config.windowsEndpointFile().ifPresentOrElse(
                file -> publishToWindows(file, endpoint),
                () -> log.warn(
                        "perfil do Windows desconhecido: o núcleo sobe mas fica indescobrível "
                                + "pelos clientes (defina ZORDON_WINDOWS_HOME na instalação)"));
        return endpoint;
    }

    private void publishToWindows(Path file, EndpointFile endpoint) {
        try {
            store.write(file, endpoint);
            log.info("endpoint publicado em {}", file);
        } catch (RuntimeException e) {
            // /mnt/c pode não estar montado. O núcleo continua útil localmente e o
            // estado é reportado como degradado, em vez de a inicialização falhar.
            log.warn("não foi possível publicar o endpoint no perfil do Windows: {}", e.getMessage());
        }
    }

    /** Loopback primeiro, depois o IP da VM: em modo NAT o cliente precisa do segundo. */
    private List<String> candidateEndpoints(int port) {
        List<String> endpoints = new ArrayList<>();
        endpoints.add(url("127.0.0.1", port));
        localAddress()
                .map(host -> url(host, port))
                .filter(url -> !endpoints.contains(url))
                .ifPresent(endpoints::add);
        return endpoints;
    }

    private String url(String host, int port) {
        return "ws://" + host + ":" + port + ZwpProtocol.PATH;
    }

    /**
     * Endereço da interface da rota padrão. Sem tabela de rotas legível, cai para a
     * primeira interface que não seja ponte virtual — melhor um palpite filtrado do
     * que o endereço de uma ponte do Docker.
     */
    private Optional<String> localAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .filter(EndpointPublisher::isUsable)
                    .toList();
            Optional<String> routed = defaultRouteInterface();
            return interfaces.stream()
                    .filter(network -> routed.map(name -> name.equals(network.getName()))
                            .orElse(!isVirtualBridge(network.getName())))
                    .flatMap(network -> Collections.list(network.getInetAddresses()).stream())
                    .filter(Inet4Address.class::isInstance)
                    .filter(address -> !address.isLoopbackAddress())
                    .map(InetAddress::getHostAddress)
                    .findFirst();
        } catch (SocketException e) {
            log.warn("não foi possível descobrir o endereço da VM: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> defaultRouteInterface() {
        try {
            return DefaultRoute.interfaceName(java.nio.file.Files.readAllLines(ROUTE_TABLE));
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean isVirtualBridge(String name) {
        return name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth")
                || name.startsWith("virbr");
    }

    private static boolean isUsable(NetworkInterface network) {
        try {
            return network.isUp() && !network.isLoopback();
        } catch (SocketException e) {
            return false;
        }
    }
}
