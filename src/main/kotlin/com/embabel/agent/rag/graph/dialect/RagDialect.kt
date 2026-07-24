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

import org.drivine.connection.DatabaseType

/**
 * Strategy interface for database-specific RAG operations.
 *
 * Implementations encapsulate the Cypher dialect differences between Neo4j,
 * FalkorDB, and Memgraph for vector/fulltext search, embedding storage, and the
 * per-engine vector literal. Schema creation itself is handled by Drivine's
 * [org.drivine.schema.IndexManager] / [org.drivine.schema.ConstraintManager]
 * (see [com.embabel.agent.rag.graph.DrivineStore.provision]), not this interface.
 *
 * Resolve the appropriate dialect from Drivine's [DatabaseType] via
 * [RagDialect.forDatabaseType].
 */
interface RagDialect {

    val name: String

    /**
     * Returns the Cypher query template for chunk vector search.
     *
     * Available parameters: `$vectorIndex`, `$chunkLabel`, `$topK`,
     * `$queryVector`, `$similarityThreshold`.
     */
    fun chunkVectorSearchCypher(): String

    /**
     * Returns the Cypher query template for chunk fulltext search, or null if
     * the database does not support fulltext search.
     *
     * Available parameters: `$fulltextIndex`, `$chunkLabel`, `$searchText`,
     * `$topK`, `$similarityThreshold`.
     */
    fun chunkFullTextSearchCypher(): String?

    /**
     * Returns the Cypher query template for entity vector search.
     *
     * Available parameters: `$index`, `$entityNodeName`, `$topK`,
     * `$queryVector`, `$similarityThreshold`, `$labels`.
     */
    fun entityVectorSearchCypher(): String

    /**
     * Returns the Cypher query template for entity fulltext search, or null if
     * the database does not support fulltext search.
     *
     * Available parameters: `$fulltextIndex`, `$entityNodeName`, `$searchText`,
     * `$similarityThreshold`, `$labels`.
     */
    fun entityFullTextSearchCypher(): String?

    /**
     * Returns the Cypher for storing an embedding on a node with the given labels.
     *
     * For Neo4j, Memgraph and FalkorDB the embedding is set as a node property.
     *
     * Templates also implement the `_text` convention: when the embedded text
     * (`$embeddedText`) differs from `n.text`, it is stored on `n._text`;
     * otherwise `n._text` is removed. Reembed callers reconstruct the
     * embedded value via `coalesce(n._text, n.text)`.
     *
     * @param labels Colon-separated label string (e.g., `"Chunk:ContentElement"`)
     *
     * Expected parameters: `$id`, `$embedding`, `$embeddingModel`, `$embeddedText`.
     */
    fun storeEmbeddingCypher(labels: String): String

    /**
     * The right-hand side expression for assigning an embedding parameter to a node property.
     *
     * FalkorDB requires the vector be wrapped in `vecf32(...)` for its vector index to pick it up
     * (a plain array is silently stored but never indexed); Neo4j and Memgraph store the plain
     * array. Used by every write path that stores an embedding — the initial embed
     * ([storeEmbeddingCypher]) and re-embed — so vector storage stays correct on every engine.
     *
     * @param paramName the bind-parameter name (without `$`) holding the embedding, e.g. `"embedding"`
     */
    fun embeddingLiteral(paramName: String): String = "\$$paramName"

    companion object {

        fun forDatabaseType(type: DatabaseType): RagDialect = when (type) {
            DatabaseType.NEO4J -> Neo4jRagDialect()
            DatabaseType.FALKORDB -> FalkorDbRagDialect()
            DatabaseType.MEMGRAPH -> MemgraphRagDialect()
            else -> throw IllegalArgumentException(
                "No RAG dialect for database type: $type. Supported types: NEO4J, FALKORDB, MEMGRAPH"
            )
        }

        /**
         * Resolve a dialect by name. Useful for databases like Memgraph that
         * share a Drivine [DatabaseType] (NEO4J/Bolt) but need a different dialect.
         */
        fun forName(name: String): RagDialect = when (name.uppercase()) {
            "NEO4J" -> Neo4jRagDialect()
            "FALKORDB" -> FalkorDbRagDialect()
            "MEMGRAPH" -> MemgraphRagDialect()
            else -> throw IllegalArgumentException(
                "No RAG dialect named: $name. Supported: NEO4J, FALKORDB, MEMGRAPH"
            )
        }
    }
}
