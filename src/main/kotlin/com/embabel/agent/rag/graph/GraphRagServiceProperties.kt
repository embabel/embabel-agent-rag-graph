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

    /**
     * How much wider than `topK` a **filtered** vector search asks the index for.
     *
     * `k` handed to a vector index is the HNSW search beam width, not a row count, and `where { }`
     * predicates apply *after* the index yields — so a scoped caller receives roughly
     * `k × selectivity` rows. Measured on a 9k-vector, 1536-dim cosine index (see
     * `Neo4jVectorRecallSearchKTest`): a caller asking for 40 got back **25**, holding only 25 of the
     * true scoped top 40 — recall 0.625. At a beam of 200 the same query returned 40/40 at recall
     * 1.0. Hence the default of 5.
     *
     * This is deliberately policy *here* rather than in Drivine, which declines to infer a
     * cost/correctness tradeoff from a selectivity estimate it does not have. This store knows it is
     * filtering, so it can make the call. The right value is data-dependent: raise it for highly
     * selective filters; set it to 1 to disable over-fetch and emit exactly what earlier versions did.
     *
     * Only the filtered path over-fetches. Unfiltered search already returns `topK` of `topK`, so a
     * wider beam there is cost without a contract to repair.
     */
    var filteredSearchOverFetch: Int = 5

    /**
     * Ceiling on the over-fetched beam, regardless of [filteredSearchOverFetch].
     *
     * Over-fetching is not free: Drivine re-ranks the widened beam by exact similarity, which reads
     * the full embedding property off every candidate. At the default multiplier a `topK` of 40 costs
     * 200 such reads; uncapped, a `topK` of 1000 would cost 5000. A `topK` at or above this ceiling
     * does not over-fetch at all — the beam is never narrowed below what the caller asked for.
     */
    var maxFilteredSearchK: Int = 500

    /**
     * The beam width to ask the index for when serving a filtered search for [topK] rows, or `null`
     * to leave the search untuned — which keeps the emitted Cypher byte-identical to the unfiltered
     * shape, rather than differently shaped.
     */
    fun filteredSearchK(topK: Int): Int? {
        if (filteredSearchOverFetch <= 1) return null
        val widened = topK.toLong() * filteredSearchOverFetch
        val capped = widened.coerceAtMost(maxFilteredSearchK.toLong()).toInt()
        // Never below topK: Drivine rejects that outright, and it could only lose results.
        return capped.coerceAtLeast(topK).takeIf { it > topK }
    }

    override fun toString(): String {
        return "${javaClass.simpleName}(chunkNodeName='$chunkNodeName', entityNodeName='$entityNodeName', name='$name', description='$description', contentElementIndex='$contentElementIndex', entityIndex='$entityIndex', contentElementFullTextIndex='$contentElementFullTextIndex', entityFullTextIndex='$entityFullTextIndex', queryMode=$queryMode, filteredSearchOverFetch=$filteredSearchOverFetch, maxFilteredSearchK=$maxFilteredSearchK)"
    }
}
