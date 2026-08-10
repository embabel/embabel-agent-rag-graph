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
 * FalkorDB RAG dialect implementation.
 *
 * Key search differences from Neo4j:
 * - Vector search: `CALL db.idx.vector.queryNodes(label, property, k, vecf32(vector))` — label and
 *   property as string arguments, `vecf32()` wrapper required, and the yielded `score` is a
 *   *distance* (converted to similarity as `1 - score`).
 * - Fulltext search: `CALL db.idx.fulltext.queryNodes(label, query)` — label as string argument
 *   instead of an index name.
 * - Embeddings must be stored as `vecf32(...)` for the vector index to pick them up (see
 *   [embeddingLiteral]).
 *
 * Schema creation is handled by Drivine's schema managers (see
 * [com.embabel.agent.rag.graph.DrivineStore.provision]); on FalkorDB Drivine issues the
 * `GRAPH.CONSTRAINT CREATE` Redis command and its required backing index, which this dialect
 * previously could not.
 *
 * @see <a href="https://docs.falkordb.com/cypher/indexing/vector-index.html">FalkorDB Vector Index</a>
 * @see <a href="https://docs.falkordb.com/cypher/indexing/fulltext-index.html">FalkorDB Fulltext Index</a>
 */
class FalkorDbRagDialect : RagDialect {

    override val name = "FalkorDB"

    override fun chunkVectorSearchCypher(): String = """
        CALL db.idx.vector.queryNodes(${'$'}chunkLabel, 'embedding', ${'$'}topK, vecf32(${'$'}queryVector))
        YIELD node AS chunk, score
        WITH chunk, (1.0 - score) AS similarity
          WHERE similarity >= ${'$'}similarityThreshold
        RETURN {
                 text:  chunk.text,
                 id:    chunk.id,
                 score: similarity
               } AS result
          ORDER BY result.score DESC""".trimIndent()

    override fun chunkFullTextSearchCypher(): String = """
        CALL db.idx.fulltext.queryNodes(${'$'}chunkLabel, ${'$'}searchText)
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
        CALL db.idx.vector.queryNodes(${'$'}entityNodeName, 'embedding', ${'$'}topK, vecf32(${'$'}queryVector))
        YIELD node AS m, score
        WITH m, (1.0 - score) AS similarity
          WHERE similarity >= ${'$'}similarityThreshold
          AND any(label IN labels(m) WHERE label IN ${'$'}labels)
        RETURN {
                 properties:  properties(m),
                 name:        COALESCE(m.name, ''),
                 description: COALESCE(m.description, ''),
                 id:          COALESCE(m.id, ''),
                 labels:      labels(m),
                 score:       similarity
               } AS result
          ORDER BY result.score DESC""".trimIndent()

    override fun entityFullTextSearchCypher(): String = """
        CALL db.idx.fulltext.queryNodes(${'$'}entityNodeName, ${'$'}searchText)
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

    /** FalkorDB indexes a vector property only when it is stored as `vecf32(...)`. */
    override fun embeddingLiteral(paramName: String): String = "vecf32(\$$paramName)"

    override fun storeEmbeddingCypher(labels: String): String = """
        MERGE (n:$labels {id: ${'$'}id})
        SET n.embedding = ${embeddingLiteral("embedding")},
         n.embeddingModel = ${'$'}embeddingModel,
         n.embeddedAt = timestamp()
        FOREACH (x IN CASE WHEN coalesce(n.text, '') = ${'$'}embeddedText THEN [1] ELSE [] END |
            REMOVE n._text
        )
        FOREACH (x IN CASE WHEN coalesce(n.text, '') <> ${'$'}embeddedText THEN [1] ELSE [] END |
            SET n._text = ${'$'}embeddedText
        )
        RETURN {nodesUpdated: COUNT(n) }""".trimIndent()
}
