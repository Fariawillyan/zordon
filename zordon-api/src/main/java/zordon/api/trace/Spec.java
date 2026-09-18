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
package zordon.api.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Liga uma classe à SPEC que a originou (docs/process/traceability.md §4).
 *
 * <p>Vai apenas nas classes <strong>principais</strong> da SPEC. Anotar tudo vira
 * ruído e deixa de ser sinal.
 *
 * <p>A anotação é rastreabilidade, nunca autoridade: ela não concede nada. Uma
 * classe que se declarasse isenta de revisão não seria obedecida por componente
 * nenhum.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Spec {

    /** Identificador no formato {@code SPEC-001}. Referência órfã reprova o build. */
    String value();
}
