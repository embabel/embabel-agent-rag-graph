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
 * Neo4j RAG dialect implementation.
 *
 * Uses the `db.index.vector.queryNodes` / `db.index.fulltext.queryNodes` procedures for search and
 * stores embeddings as a plain node property. Schema creation is handled by Drivine's schema
 * managers (see [com.embabel.agent.rag.graph.DrivineStore.provision]).
 */
class Neo4jRagDialect : RagDialect {

    override val name = "Neo4j"

    override fun chunkVectorSearchCypher(): String = """
        CALL db.index.vector.queryNodes(${'$'}vectorIndex, ${'$'}topK, ${'$'}queryVector)
        YIELD node AS chunk, score
          WHERE score >= ${'$'}similarityThreshold
        RETURN {
                 text:  chunk.text,
                 id:    chunk.id,
                 score: score
               } AS result
          ORDER BY result.score DESC""".trimIndent()

    override fun chunkFullTextSearchCypher(): String = """
        CALL db.index.fulltext.queryNodes(${'$'}fulltextIndex, ${'$'}searchText)
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
        CALL db.index.vector.queryNodes(${'$'}index, ${'$'}topK, ${'$'}queryVector)
        YIELD node AS m, score
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
        CALL db.index.fulltext.queryNodes(${'$'}fulltextIndex, ${'$'}searchText)
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
        SET n.embedding = ${'$'}embedding,
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
