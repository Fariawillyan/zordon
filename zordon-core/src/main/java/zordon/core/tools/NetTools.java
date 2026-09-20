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
package zordon.core.tools;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.security.Gatekeeper;

/** {@code http.check}: a API está de pé? (SPEC-025, UC6). */
@Spec("SPEC-025")
public final class NetTools {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int BODY_LIMIT = 64 * 1024;
    static final int SNIPPET = 300;

    public static Tool httpCheck() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new Tool() {
            @Override public String name() { return "http.check"; }

            @Override
            public String description() {
                return "Confere se um endereço HTTP responde (GET): status, tempo e o começo da resposta.";
            }

            // O corpo é conteúdo de terceiros: contamina o turno como leitura de arquivo.
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(Effect.NETWORK, Effect.READ_FS); }
            @Override public Map<String, Object> inputSchema() { return Tool.schema("url", "endereço http ou https"); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                URI uri = parse(Tool.text(args, "url"));
                // Endereço local ou da rede privada: GREEN. O resto é saída para a internet: YELLOW.
                RiskLevel risk = local(uri.getHost()) ? RiskLevel.GREEN : RiskLevel.YELLOW;
                return new ActionDescriptor(name(), Map.of("url", uri.toString()), risk, effects(), List.of(), 1,
                        List.of(), "Conferir " + uri);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                URI uri = URI.create(String.valueOf(permit.action().args().get("url")));
                if (permit.action().baseRisk() == RiskLevel.GREEN && !local(uri.getHost())) {
                    // O nome passou a apontar para fora entre a autorização e a chamada.
                    throw new ToolException("o endereço " + uri.getHost() + " deixou de ser local");
                }
                long started = System.nanoTime();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("url", uri.toString());
                try {
                    HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                            .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                    String body;
                    try (InputStream in = response.body()) {
                        body = new String(in.readNBytes(BODY_LIMIT), StandardCharsets.UTF_8);
                    }
                    long ms = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    int status = response.statusCode();
                    data.put("status", status);
                    data.put("ms", ms);
                    data.put("ok", status >= 200 && status < 300);
                    String snippet = body.strip().replaceAll("\\s+", " ");
                    snippet = snippet.length() > SNIPPET ? snippet.substring(0, SNIPPET) + "…" : snippet;
                    return new ToolResult("HTTP " + status + " em " + ms + " ms" + (snippet.isEmpty() ? "." : ": "
                            + snippet), data);
                } catch (IOException e) {
                    // Sem resposta é um resultado, não uma falha da ferramenta: é o que a automação quer saber.
                    data.put("status", 0);
                    data.put("ms", Duration.ofNanos(System.nanoTime() - started).toMillis());
                    data.put("ok", false);
                    return new ToolResult("Sem resposta de " + uri + ": " + reason(e) + ".", data);
                }
            }
        };
    }

    static URI parse(String raw) throws ToolException {
        URI uri;
        try {
            uri = URI.create(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new ToolException("endereço inválido: " + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new ToolException("só http ou https, com host: " + raw);
        }
        if (uri.getUserInfo() != null) {
            throw new ToolException("endereço com usuário e senha não é aceito");
        }
        return uri;
    }

    /** Loopback, link-local ou rede privada, em todos os endereços do nome. */
    static boolean local(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (!(address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || isUniqueLocal(address))) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** IPv6 fc00::/7, o equivalente da rede privada. */
    private static boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static String reason(IOException e) {
        String name = e.getClass().getSimpleName();
        return switch (name) {
            case "ConnectException" -> "conexão recusada";
            case "HttpTimeoutException", "HttpConnectTimeoutException" -> "passou do prazo";
            default -> e.getMessage() == null ? name : e.getMessage();
        };
    }

    private NetTools() {}
}
