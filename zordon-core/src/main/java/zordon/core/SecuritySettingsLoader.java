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
package zordon.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.security.SecuritySettings;

/** Carrega a política uma vez e mantém o fallback restritivo quando o TOML é inválido. */
final class SecuritySettingsLoader {

    private static final Logger log = LoggerFactory.getLogger(SecuritySettingsLoader.class);

    private final Path config;
    private final String home;
    private final String path;
    private SecuritySettings loaded;

    SecuritySettingsLoader(Path config, Map<String, String> environment) {
        this.config = config;
        this.home = environment.getOrDefault("HOME", System.getProperty("user.home"));
        this.path = environment.get("PATH");
    }

    synchronized SecuritySettings load() {
        if (loaded == null) {
            try {
                loaded = SecuritySettings.load(config, home, path);
            } catch (IOException e) {
                log.warn("política de segurança do config.toml ignorada: {}", e.getMessage());
                try {
                    loaded = SecuritySettings.load(config.resolveSibling("config.toml.ausente"), home, path);
                } catch (IOException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        return loaded;
    }
}
