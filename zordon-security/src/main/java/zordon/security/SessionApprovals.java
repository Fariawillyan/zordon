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
package zordon.security;

import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;

/**
 * O que a resposta da tela — ou a falta dela — significa, e as permissões
 * "nesta sessão" de YELLOW que ela deixa valendo: (ferramenta, área), até o
 * núcleo reiniciar.
 */
final class SessionApprovals {

    private final Set<String> grants = ConcurrentHashMap.newKeySet();

    boolean granted(ActionDescriptor action) {
        return grants.contains(grantKey(action));
    }

    /** Sem resposta é negação. */
    Decision settle(ActionDescriptor action, Decision.AskUser ask, PermissionEngine.Approval approval,
            Throwable failure) {
        Throwable cause = failure instanceof CompletionException wrapped
                && wrapped.getCause() != null ? wrapped.getCause() : failure;
        if (cause instanceof TimeoutException || (failure == null && approval == null)) {
            return new Decision.Deny(ask.risk(), "sem resposta em " + ask.ttl().toSeconds() + " s");
        }
        if (failure != null) {
            return new Decision.Deny(ask.risk(), "sem tela para autorizar: " + cause.getMessage());
        }
        return answered(action, ask, approval);
    }

    private Decision answered(ActionDescriptor action, Decision.AskUser ask, PermissionEngine.Approval approval) {
        return switch (approval) {
            case DENY -> new Decision.Deny(ask.risk(), "negado pelo usuário");
            case ONCE -> new Decision.Allow(ask.risk(), "autorizado pelo usuário", false);
            case SESSION -> {
                if (ask.perAction()) {
                    yield new Decision.Allow(ask.risk(), "autorizado pelo usuário, só desta vez", false);
                }
                grants.add(grantKey(action));
                yield new Decision.Allow(ask.risk(), "autorizado nesta sessão", true);
            }
        };
    }

    /** Área de uma permissão de sessão: a ferramenta e a pasta do primeiro caminho. */
    private static String grantKey(ActionDescriptor action) {
        String area = action.touchedPaths().isEmpty() ? "" : parent(action.touchedPaths().getFirst().comparable());
        return action.tool() + "|" + area;
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? path : path.substring(0, slash);
    }
}
