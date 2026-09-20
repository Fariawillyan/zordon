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
package zordon.defense;

/**
 * Quem ou o quê está sob suspeita (Defesa §4). Sinais se juntam por sujeito: é o
 * que separa "três coisas estranhas" de "um processo estranho fazendo três coisas".
 */
public record Subject(String kind, String id) {

    public static Subject agent(String id) {
        return new Subject("agent", id);
    }

    public static Subject mcp(String server) {
        return new Subject("mcp", server);
    }

    public static Subject turn(String turnId) {
        return new Subject("turn", turnId);
    }

    public static Subject actor(String actor) {
        return new Subject("actor", actor);
    }

    public static Subject self() {
        return new Subject("self", "zordon");
    }

    public static Subject file(String path) {
        return new Subject("file", path);
    }

    public static Subject process(String pid) {
        return new Subject("process", pid);
    }

    @Override
    public String toString() {
        return kind + ":" + id;
    }
}
