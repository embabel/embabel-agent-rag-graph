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

import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex

/**
 * Where a chunk sits, and nothing else: enough to link it to its parent and to the chunk after it
 * without loading its text or its vector.
 *
 * Its `id` is range-indexed because the next-chunk link MATCHes both chunks by it under `Chunk` alone,
 * which the `ContentElement` index does not serve. The store provisions this index from the annotation.
 */
@NodeFragment(labels = ["Chunk"])
data class ChunkPlaceFragment(
    @NodeId @RangeIndex val id: String,
    val parentId: String? = null,
    @GraphProperty("root_document_id") val rootDocumentId: String? = null,
    @GraphProperty("container_section_id") val containerSectionId: String? = null,
    @GraphProperty("leaf_section_id") val leafSectionId: String? = null,
    @GraphProperty("sequence_number") val sequenceNumber: Long? = null,
)
