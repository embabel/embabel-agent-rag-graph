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
 * `expand` as an **edge traversal** (Drivine 0.0.65+): from an anchor chunk, walk the `NEXT_CHUNK`
 * chain forward ([following]) and backward ([preceding]) up to `maxDepth` hops — a variable-length
 * `*1..N` neighbourhood collected as a flat list, replacing the `sequence_number` range scan. The
 * window is overridable per query via `depth("following", n)` so the caller's `elementsToAdd` drives it.
 */
@GraphView
data class ChunkExpandView(
    @Root val chunk: ChunkNode,
    @GraphRelationship(type = "NEXT_CHUNK", direction = Direction.OUTGOING, maxDepth = 50)
    val following: List<ChunkNode>,
    @GraphRelationship(type = "NEXT_CHUNK", direction = Direction.INCOMING, maxDepth = 50)
    val preceding: List<ChunkNode>,
)
