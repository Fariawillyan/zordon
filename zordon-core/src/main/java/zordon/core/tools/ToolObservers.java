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

import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Effect;
import zordon.api.security.Principal;

/**
 * Quem acompanha as chamadas sem fazer parte delas: a defesa (SPEC-026) e a
 * memória do trabalho (SPEC-021 CA-7). Uma falha de observador nunca derruba a
 * chamada — vira log e segue.
 */
final class ToolObservers {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntime.class);

    private volatile SkillRuntime.CallObserver observer = new SkillRuntime.CallObserver() {
        @Override
        public void call(ActionDescriptor action, Principal actor, String turnId, Decision decision,
                Set<Effect> declared, boolean tainted) {
        }

        @Override
        public void result(ActionDescriptor action, String turnId, String text, String status) {
        }
    };
    private volatile BiConsumer<ActionDescriptor, String> completed = (action, turnId) -> { };

    void watch(SkillRuntime.CallObserver watcher) {
        this.observer = Objects.requireNonNull(watcher, "watcher");
    }

    void onCompleted(BiConsumer<ActionDescriptor, String> listener) {
        this.completed = Objects.requireNonNull(listener, "observer");
    }

    void decided(Consumer<Decision> decided, Decision decision) {
        try {
            decided.accept(decision);
        } catch (RuntimeException e) {
            log.debug("observador de decisão falhou: {}", e.getMessage());
        }
    }

    void called(ActionDescriptor action, Principal actor, String turnId, Decision decision, Set<Effect> declared,
            boolean tainted) {
        try {
            observer.call(action, actor, turnId, decision, declared, tainted);
        } catch (RuntimeException e) {
            log.debug("observador de chamada falhou: {}", e.getMessage());
        }
    }

    void completed(ActionDescriptor action, String turnId) {
        try {
            completed.accept(action, turnId);
        } catch (RuntimeException e) {
            log.debug("observador de ferramenta falhou: {}", e.getMessage());
        }
    }

    void result(ActionDescriptor action, String turnId, String text, String status) {
        try {
            observer.result(action, turnId, text, status);
        } catch (RuntimeException e) {
            log.debug("observador de resultado falhou: {}", e.getMessage());
        }
    }
}
