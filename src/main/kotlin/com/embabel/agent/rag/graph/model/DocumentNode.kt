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

import com.embabel.agent.rag.model.MaterializedDocument
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import java.time.Instant

/**
 * Drivine `@NodeFragment` model for a persisted document root ([MaterializedDocument]).
 *
 * @see ChunkNode for the metadata / `from` / `toCoreType` conventions.
 */
@NodeFragment(labels = ["Document", "ContentRoot"])
data class DocumentNode(
    @NodeId override val id: String,
    override val uri: String,
    val title: String,
    @GraphProperty("ingestionTimestamp") val ingestionTimestampMillis: Long? = null,
    @PropertyBag(prefix = "metadata") val metadata: Map<String, Any?> = emptyMap(),
) : ContentElementNode {

    override fun toCoreType(): MaterializedDocument = MaterializedDocument(
        id = id,
        uri = uri,
        title = title,
        ingestionTimestamp = ingestionTimestampMillis?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
        children = emptyList(),
        metadata = metadata,
    )

    companion object {
        fun from(document: MaterializedDocument): DocumentNode = DocumentNode(
            id = document.id,
            uri = document.uri,
            title = document.title,
            ingestionTimestampMillis = document.ingestionTimestamp.toEpochMilli(),
            metadata = document.metadata,
        )
    }
}
