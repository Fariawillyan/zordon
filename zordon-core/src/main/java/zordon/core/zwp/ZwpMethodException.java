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

import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpErrorKind;

/** Falha que um método quer devolver ao cliente com um {@code kind} específico. */
public class ZwpMethodException extends RuntimeException {

    private final transient ZwpError error;

    public ZwpMethodException(ZwpErrorKind kind, String message) {
        super(message);
        this.error = ZwpError.of(kind, message);
    }

    public ZwpMethodException(ZwpError error) {
        super(error.message());
        this.error = error;
    }

    public ZwpError error() {
        return error;
    }
}
