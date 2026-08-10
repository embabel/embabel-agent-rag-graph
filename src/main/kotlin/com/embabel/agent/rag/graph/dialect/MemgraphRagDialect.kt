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
package com.embabel.agent.rag.graph.dialect

/**
 * Memgraph RAG dialect implementation.
 *
 * Memgraph is openCypher-native with Bolt protocol support, so most Cypher works identically to
 * Neo4j. Key search differences:
 *
 * - Vector search: `CALL vector_search.search(indexName, k, vector) YIELD node, similarity`
 *   (yields `similarity` not `score`)
 * - Fulltext search: `CALL text_search.search_all(indexName, query) YIELD node, score`
 *
 * Schema creation is handled by Drivine's schema managers (see
 * [com.embabel.agent.rag.graph.DrivineStore.provision]).
 *
 * @see <a href="https://memgraph.com/docs/querying/vector-search">Memgraph Vector Search</a>
 * @see <a href="https://memgraph.com/docs/querying/text-search">Memgraph Text Search</a>
 */
class MemgraphRagDialect : RagDialect {

    override val name = "Memgraph"

    override fun chunkVectorSearchCypher(): String = """
        CALL vector_search.search(${'$'}vectorIndex, ${'$'}topK, ${'$'}queryVector)
        YIELD node AS chunk, similarity AS score
        WITH chunk, score
          WHERE score >= ${'$'}similarityThreshold
        RETURN {
                 text:  chunk.text,
                 id:    chunk.id,
                 score: score
               } AS result
          ORDER BY result.score DESC""".trimIndent()

    override fun chunkFullTextSearchCypher(): String = """
        CALL text_search.search_all(${'$'}fulltextIndex, ${'$'}searchText)
        YIELD node AS chunk, score
        WITH chunk, score / (score + $bm25K) AS normalizedScore
          WHERE normalizedScore >= ${'$'}similarityThreshold
        RETURN {
                 text: chunk.text,
                 id:   chunk.id,
                 score: normalizedScore
               } AS result
          ORDER BY result.score DESC
          LIMIT ${'$'}topK""".trimIndent()

    override fun entityVectorSearchCypher(): String = """
        CALL vector_search.search(${'$'}index, ${'$'}topK, ${'$'}queryVector)
        YIELD node AS m, similarity AS score
        WITH m, score
          WHERE score >= ${'$'}similarityThreshold
          AND any(label IN labels(m) WHERE label IN ${'$'}labels)
        RETURN {
                 properties:  properties(m),
                 name:        COALESCE(m.name, ''),
                 description: COALESCE(m.description, ''),
                 id:          COALESCE(m.id, ''),
                 labels:      labels(m),
                 score:       score
               } AS result
          ORDER BY result.score DESC""".trimIndent()

    override fun entityFullTextSearchCypher(): String = """
        CALL text_search.search_all(${'$'}fulltextIndex, ${'$'}searchText)
        YIELD node AS m, score
        WHERE score IS NOT NULL AND any(label IN labels(m) WHERE label IN ${'$'}labels)
        WITH m AS match,
             score / (score + $bm25K) AS score,
             m.name AS name,
             m.description AS description,
             m.id AS id,
             labels(m) AS labels
          WHERE score >= ${'$'}similarityThreshold
        RETURN {
                 name:        COALESCE(name, ''),
                 description: COALESCE(description, ''),
                 id:          COALESCE(id, ''),
                 properties:  properties(match),
                 labels:      labels,
                 score:       score
               } AS result
          ORDER BY result.score DESC""".trimIndent()

    override fun storeEmbeddingCypher(labels: String): String = """
        MERGE (n:$labels {id: ${'$'}id})
        SET n.$EMBEDDING_PROPERTY = ${'$'}embedding,
         n.embeddingModel = ${'$'}embeddingModel,
         n.embeddedAt = timestamp()
        FOREACH (x IN CASE WHEN coalesce(n.text, '') = ${'$'}embeddedText THEN [1] ELSE [] END |
            REMOVE n._text
        )
        FOREACH (x IN CASE WHEN coalesce(n.text, '') <> ${'$'}embeddedText THEN [1] ELSE [] END |
            SET n._text = ${'$'}embeddedText
        )
        RETURN {nodesUpdated: COUNT(n) }""".trimIndent()

    companion object {
        private const val EMBEDDING_PROPERTY = "embedding"
    }
}
