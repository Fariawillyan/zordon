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
package zordon.ai.registry;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Referência a um segredo — nunca o segredo.
 *
 * <p>O {@code config.toml} guarda {@code api_key = "env:OPENAI_API_KEY"}, e o valor
 * vem do ambiente do processo. Um valor literal é recusado: o arquivo de
 * configuração precisa poder ser copiado, versionado ou colado num pedido de ajuda
 * sem vazar nada ([Segurança §5](../../../../../docs/security/model.md#5-segredos)).
 * O esquema {@code secret://}, do Credential Manager, entra neste mesmo tipo quando
 * existir.
 */
public record SecretRef(String variable) {

    private static final String ENV_SCHEME = "env:";
    private static final Pattern VARIABLE = Pattern.compile("[A-Z_][A-Z0-9_]*");

    public SecretRef {
        if (variable == null || !VARIABLE.matcher(variable).matches()) {
            throw new IllegalArgumentException("nome de variável inválido para segredo");
        }
    }

    /**
     * Lê uma referência. A mensagem de erro <strong>nunca repete o valor
     * recebido</strong>: se ele for uma chave colada por engano, repeti-la no log
     * seria vazá-la no único lugar em que o usuário não pensaria em procurar.
     */
    public static SecretRef parse(String reference) {
        if (reference == null || !reference.startsWith(ENV_SCHEME)) {
            throw new IllegalArgumentException(
                    "config.toml não guarda segredo: use api_key = \"env:NOME_DA_VARIAVEL\" "
                            + "e ponha o valor em ~/.zordon/secrets.env");
        }
        return new SecretRef(reference.substring(ENV_SCHEME.length()));
    }

    public Optional<String> resolve(Map<String, String> environment) {
        return Optional.ofNullable(environment.get(variable)).map(String::strip).filter(value -> !value.isEmpty());
    }

    @Override
    public String toString() {
        return ENV_SCHEME + variable;
    }
}
