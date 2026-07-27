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

import com.embabel.agent.rag.model.LeafSection
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag

/**
 * Drivine `@NodeFragment` model for a persisted [LeafSection].
 *
 * @see ChunkNode for the metadata / `from` / `toCoreType` conventions.
 */
@NodeFragment(labels = ["LeafSection"])
data class LeafSectionNode(
    @NodeId override val id: String,
    val title: String,
    val text: String,
    val parentId: String? = null,
    override val uri: String? = null,
    @PropertyBag(prefix = "metadata") val metadata: Map<String, Any?> = emptyMap(),
) : ContentElementNode {

    override fun toCoreType(): LeafSection = LeafSection(
        id = id,
        uri = uri,
        title = title,
        text = text,
        parentId = parentId,
        metadata = metadata,
    )

    companion object {
        fun from(section: LeafSection): LeafSectionNode = LeafSectionNode(
            id = section.id,
            title = section.title,
            text = section.text,
            parentId = section.parentId,
            uri = section.uri,
            metadata = section.metadata,
        )
    }
}
