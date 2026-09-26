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

import java.util.List;
import zordon.api.event.EventEnvelope;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.HelloResult;
import zordon.api.zwp.ProtocolRange;
import zordon.api.zwp.ResumeRequest;
import zordon.api.zwp.ZwpProtocol;

/** O que permite retomar a sessão depois de uma queda (ADR-0011): qual núcleo e o último evento visto. */
final class SessionResume {

    private final ClientInfo client;
    private final List<String> capabilities;
    private volatile String lastStartId;
    private volatile long lastEventSeq;

    SessionResume(ClientInfo client, List<String> capabilities) {
        this.client = client;
        this.capabilities = List.copyOf(capabilities);
    }

    void seen(EventEnvelope event) {
        lastEventSeq = Math.max(lastEventSeq, event.seq());
    }

    HelloParams hello() {
        ResumeRequest resume = lastStartId != null && lastEventSeq > 0
                ? new ResumeRequest(lastStartId, lastEventSeq)
                : null;
        return new HelloParams(client, ProtocolRange.exactly(ZwpProtocol.VERSION), capabilities, resume);
    }

    /** @return se houve continuidade; quando não, o cliente descarta o estado volátil e recarrega */
    boolean online(HelloResult hello) {
        boolean sameCore = hello.core().startId().equals(lastStartId);
        boolean resumed = hello.resumed() && sameCore;
        if (!resumed) {
            lastEventSeq = 0;
        }
        lastStartId = hello.core().startId();
        return resumed;
    }
}
