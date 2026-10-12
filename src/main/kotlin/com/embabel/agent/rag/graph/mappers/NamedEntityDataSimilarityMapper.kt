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
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import org.drivine.mapper.RowMapper

internal class NamedEntityDataSimilarityMapper : RowMapper<SimilarityResult<NamedEntityData>> {

    @Suppress("UNCHECKED_CAST")
    override fun map(row: Map<String, *>): SimilarityResult<NamedEntityData> {
        val match = SimpleNamedEntityData(
            id = row["id"] as String,
            name = row["name"] as String,
            description = row["description"] as? String ?: "",
            labels = (row["labels"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet(),
            properties = entityProperties(row["properties"] as? Map<String, Any>),
        )
        return SimpleSimilaritySearchResult(
            match = match,
            score = row["score"] as Double,
        )
    }
}
