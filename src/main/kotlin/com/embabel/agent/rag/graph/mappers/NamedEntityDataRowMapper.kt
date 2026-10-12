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
package com.embabel.agent.rag.graph.mappers

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import org.drivine.mapper.RowMapper
import org.drivine.model.NodeLabelsModel

internal class NamedEntityDataRowMapper : RowMapper<NamedEntityData> {

    override fun map(row: Map<String, *>): NamedEntityData {
        @Suppress("UNCHECKED_CAST")
        return SimpleNamedEntityData(
            id = row["id"] as String,
            name = row["name"] as String,
            description = row["description"] as? String ?: "",
            labels = (row["labels"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet(),
            properties = entityProperties(row["properties"] as? Map<String, Any>),
        )
    }
}

/**
 * A stored node's properties as an entity's: Drivine keeps properties of its own on a node, under
 * names beginning `__drivine.`, and they are not the entity's.
 */
internal fun entityProperties(stored: Map<String, Any>?): Map<String, Any> =
    stored.orEmpty().filterKeys { !it.startsWith(NodeLabelsModel.RESERVED_PREFIX) }
