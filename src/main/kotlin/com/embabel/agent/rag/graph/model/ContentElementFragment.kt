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

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex

/**
 * A label-generic fragment matching **any** content element — the whole hierarchy shares the
 * `ContentElement` label, so a single fragment can stand in for a Document, Section, or Chunk when
 * we only care about tree structure and common properties.
 *
 * Its `id` is range-indexed because every save of a document, section or chunk MERGEs by it under
 * `ContentElement`, and every parent link MATCHes both ends the same way: without the index, each of
 * those statements scans the label. The store provisions this index from the annotation.
 */
@NodeFragment(labels = ["ContentElement"])
data class ContentElementFragment(
    @NodeId @RangeIndex val id: String,
    val text: String? = null,
    val title: String? = null,
    val uri: String? = null,
)
