/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.rag.graph.model

import com.embabel.agent.rag.model.ContentElement
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/**
 * Polymorphic base for the persisted content-element models. The shared `ContentElement` label lives
 * here and is inherited by each subtype's own label(s) at save time; on load, Drivine dispatches by
 * the node's label set to the right concrete model (retiring the hand-rolled
 * `DefaultContentElementRowMapper` label chain). Each subtype knows how to rebuild its own core type.
 */
@NodeFragment(labels = ["ContentElement"])
sealed interface ContentElementNode {
    @get:NodeId val id: String

    /**
     * Source uri, shared by every content element. Declared on the base so the generated
     * `ContentElementNodeQueryDsl` exposes it — letting callers filter the polymorphic base by uri and
     * narrow with `instanceOf<…>()`, rather than reaching for a subtype's DSL + the explicit form.
     */
    val uri: String?

    fun toCoreType(): ContentElement
}
