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

import java.util.Objects;
import zordon.api.trace.Spec;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.change.Preflight;
import zordon.core.usage.UsageTracker;

/** {@code usage.summary} e {@code change.plan} (SPEC-029). */
@Spec("SPEC-029")
public final class UsageMethods {

    private final UsageTracker usage;
    private final Preflight preflight;

    public UsageMethods(UsageTracker usage, Preflight preflight) {
        this.usage = Objects.requireNonNull(usage, "usage");
        this.preflight = Objects.requireNonNull(preflight, "preflight");
    }

    public void registerOn(ZwpServer server) {
        server.register("usage.summary", (session, params) -> usage.summary(
                params.get("days") instanceof Number days ? days.intValue() : 7))
                .register("change.plan", (session, params) -> {
                    if (!(params.get("goal") instanceof String goal) || goal.isBlank()) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "goal é obrigatório");
                    }
                    return Preflight.wire(preflight.plan(goal));
                });
    }
}
