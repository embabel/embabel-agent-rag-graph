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

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root

/**
 * `zoomOut` as an **edge traversal**: from an anchor element, follow `HAS_PARENT` **outgoing** one hop
 * to its parent (typed, via the polymorphic [ContentElementNode]). Replaces the relational
 * `parentId`-property join — in a graph, following the edge is a pointer hop, not an id lookup.
 */
@GraphView
data class ZoomOutView(
    @Root val element: ContentElementFragment,
    @GraphRelationship(type = "HAS_PARENT", direction = Direction.OUTGOING, maxDepth = 1)
    val parent: ContentElementNode?,
)
