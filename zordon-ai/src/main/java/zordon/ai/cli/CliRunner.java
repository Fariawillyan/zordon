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
package zordon.ai.cli;

import java.time.Duration;
import java.util.List;

/**
 * Quem roda o CLI de um provider por assinatura (SPEC-018). O {@code zordon-ai}
 * não inicia processo (ADR-0007): o núcleo implementa isto pelo caminho mediado,
 * auditado, da SPEC-016.
 */
public interface CliRunner {

    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {}

    /** @param stdin o prompt: pela entrada padrão, nunca na linha de comando */
    Result run(List<String> argv, String stdin, Duration timeout) throws Exception;

    /** Se o programa pode rodar (está no catálogo). Sem ele, o provider nem é montado (SPEC-018 CA-4). */
    boolean available(String program);
}
