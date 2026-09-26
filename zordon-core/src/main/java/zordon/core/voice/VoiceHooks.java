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
package zordon.core.voice;

import java.util.function.BooleanSupplier;

/**
 * O que as partes da voz pedem ao {@link VoiceService}: o lock que protege todo o
 * estado da voz, publicar o estado, reconciliar o microfone, ressincronizar o
 * fluxo e duas perguntas sobre o estado das outras partes.
 */
record VoiceHooks(Object lock, Runnable publish, Runnable reconcile, Runnable resync, BooleanSupplier testing,
        BooleanSupplier clickActive) {}
