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

import com.embabel.agent.rag.model.NamedEntityData
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeLabels
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/**
 * A named entity as it is stored: one node under the entity label ([LABEL]), carrying its type labels and
 * whatever properties its source gave it.
 *
 * Both are open-ended. An entity's types come from extraction and from the data dictionary, not from
 * this class, so they are a [NodeLabels] set; its properties were never namespaced, so they are a
 * flat [PropertyBag] holding every property that is not one of the declared fields here.
 *
 * [name] and [description] are nullable because nodes written before either was required exist; the
 * repository reads an absent one as empty. [embedding] is declared so that it is never mistaken for
 * an open property, and is left alone by a save that does not set it.
 *
 * The label and the index names are fixed here, because a fragment's are; they are the defaults of
 * `GraphRagServiceProperties`.
 */
@NodeFragment(labels = [EntityNode.LABEL])
@FullTextIndex(properties = ["name", "description"], name = EntityNode.FULL_TEXT_INDEX)
data class EntityNode(
    @NodeId val id: String,
    val name: String? = null,
    val description: String? = null,
    val lastModifiedDate: Long? = null,
    @VectorIndex(similarity = SimilarityFunction.COSINE, name = EntityNode.VECTOR_INDEX) val embedding: List<Float>? = null,
    @NodeLabels val labels: Set<String> = emptySet(),
    @PropertyBag(flat = true) val properties: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** The label every entity node carries, and the one its indexes are declared on. */
        const val LABEL = NamedEntityData.ENTITY_LABEL

        /**
         * The names of the entity indexes, which are the defaults of
         * `GraphRagServiceProperties.entityIndex` and `entityFullTextIndex` — so a database that
         * already has them, under those names, is searched as it stands.
         */
        const val VECTOR_INDEX = "embabel_entity_index"
        const val FULL_TEXT_INDEX = "embabel_entity_fulltext_index"
    }
}
