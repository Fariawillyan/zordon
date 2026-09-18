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
package zordon.zwp;

import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpErrorKind;
import java.util.Optional;

/** O outro lado respondeu com erro. Carrega o {@code kind} para a UI decidir o que fazer. */
public class ZwpRemoteException extends RuntimeException {

    private final transient ZwpError error;

    public ZwpRemoteException(ZwpError error) {
        super(error.kind() != null ? error.kind() + ": " + error.message() : error.message());
        this.error = error;
    }

    public ZwpError error() {
        return error;
    }

    public Optional<ZwpErrorKind> kind() {
        return Optional.ofNullable(error.kind());
    }
}
