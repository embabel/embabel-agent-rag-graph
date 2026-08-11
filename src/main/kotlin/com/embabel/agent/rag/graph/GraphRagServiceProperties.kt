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
     * Whether full-text queries have their identifier-shaped tokens promoted to *required* terms
     * before they reach the index — so `what causes ER20328_23` retrieves the chunk containing that
     * code rather than everything sharing a common word with the question.
     *
     * On by default: full-text search exists for the retrieval a vector index cannot do, and this is
     * what makes it precise. A required term matching nothing falls back to the original query, so
     * recall cannot regress.
     *
     * Assumes Lucene-compatible query syntax, which holds for Neo4j full-text indexes and Memgraph's
     * Tantivy-backed `text_search`. Set false for engines whose full-text syntax differs, such as
     * RediSearch-backed FalkorDB.
     */
    var requireIdentifierTerms: Boolean = true

    override fun toString(): String {
        return "${javaClass.simpleName}(chunkNodeName='$chunkNodeName', entityNodeName='$entityNodeName', name='$name', description='$description', contentElementIndex='$contentElementIndex', entityIndex='$entityIndex', contentElementFullTextIndex='$contentElementFullTextIndex', entityFullTextIndex='$entityFullTextIndex', requireIdentifierTerms=$requireIdentifierTerms)"
    }
}
