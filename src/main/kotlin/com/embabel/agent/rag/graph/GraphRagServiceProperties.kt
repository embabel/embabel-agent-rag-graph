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
package com.embabel.agent.rag.graph

import com.embabel.agent.rag.graph.fulltext.FullTextQueryMode
import com.embabel.agent.rag.model.NamedEntityData
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @param chunkNodeName the name of the node representing a chunk in the knowledge graph
 * @param entityNodeName the name of a node representing an entity in the knowledge graph
 */
@ConfigurationProperties(prefix = "embabel.agent.rag.graph")
class GraphRagServiceProperties {

    var chunkNodeName: String = "Chunk"
    var entityNodeName: String = NamedEntityData.ENTITY_LABEL
    var name: String = "DrivineRagService"
    var description: String = "Neo RAG service using Drivine for querying and embedding"
    var contentElementIndex: String = "embabel_content_index"
    var entityIndex: String = "embabel_entity_index"
    var contentElementFullTextIndex: String = "embabel_content_fulltext_index"
    var entityFullTextIndex: String = "embabel_entity_fulltext_index"

    /**
     * How full-text queries are read — see [FullTextQueryMode].
     *
     * [FullTextQueryMode.EXPRESSION] by default: it is what this store has always done, and a
     * capable caller does compose required terms when the tool description asks (3/3 on
     * gpt-4.1-mini, against 0/3 with the notes that shipped before). Switch to
     * [FullTextQueryMode.LITERAL] for callers that cannot — a small model is likelier to emit a
     * malformed expression than a useful one, and under LITERAL it cannot emit one at all.
     */
    var queryMode: FullTextQueryMode = FullTextQueryMode.EXPRESSION

    override fun toString(): String {
        return "${javaClass.simpleName}(chunkNodeName='$chunkNodeName', entityNodeName='$entityNodeName', name='$name', description='$description', contentElementIndex='$contentElementIndex', entityIndex='$entityIndex', contentElementFullTextIndex='$contentElementFullTextIndex', entityFullTextIndex='$entityFullTextIndex', queryMode=$queryMode)"
    }
}
