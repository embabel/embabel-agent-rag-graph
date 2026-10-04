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
package com.embabel.agent.rag.graph.test

import com.embabel.agent.rag.graph.GraphRagServiceProperties
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption

/**
 * Shell commands for testing RAG operations against Neo4j.
 */
@ShellComponent
class RagShellCommands(
    private val cypherSearch: ShellCypher,
    private val properties: GraphRagServiceProperties,
    private val fakeUser: FakeUser,
) {

    @ShellMethod(value = "Show current user info", key = ["user", "whoami"])
    fun user(): String {
        return """
            |Current User:
            |  ID: ${fakeUser.id}
            |  Display Name: ${fakeUser.displayName}
            |  Persona: ${fakeUser.persona ?: "(none)"}
        """.trimMargin()
    }

    @ShellMethod(value = "Show connection and configuration info", key = ["info", "config"])
    fun info(): String {
        val neo4jUri = System.getenv("NEO4J_URI") ?: "bolt://localhost:7687"
        return """
            |Neo4j Configuration:
            |  URI: $neo4jUri
            |  Content Element Index: ${properties.contentElementIndex}
            |  Entity Index: ${properties.entityIndex}
            |  Full-text Index (Content): ${properties.contentElementFullTextIndex}
            |  Full-text Index (Entity): ${properties.entityFullTextIndex}
            |  Chunk Node Name: ${properties.chunkNodeName}
            |  Entity Node Name: ${properties.entityNodeName}
        """.trimMargin()
    }

    @ShellMethod(value = "Count chunks in the database", key = ["count-chunks", "cc"])
    fun countChunks(): String {
        val count = cypherSearch.queryForInt("MATCH (c:Chunk) RETURN count(c) AS count")
        return "Chunk count: $count"
    }

    @ShellMethod(value = "Count entities in the database", key = ["count-entities", "ce"])
    fun countEntities(): String {
        val count = cypherSearch.queryForInt("MATCH (e:Entity) RETURN count(e) AS count")
        return "Entity count: $count"
    }

    @ShellMethod(value = "Count all content elements", key = ["count-all", "ca"])
    fun countAll(): String {
        val chunks = cypherSearch.queryForInt("MATCH (c:Chunk) RETURN count(c) AS count")
        val entities = cypherSearch.queryForInt("MATCH (e:Entity) RETURN count(e) AS count")
        val documents = cypherSearch.queryForInt("MATCH (d:Document) RETURN count(d) AS count")
        return """
            |Content Summary:
            |  Chunks: $chunks
            |  Entities: $entities
            |  Documents: $documents
            |  Total: ${chunks + entities + documents}
        """.trimMargin()
    }

    @ShellMethod(value = "List recent chunks", key = ["list-chunks", "lc"])
    fun listChunks(
        @ShellOption(defaultValue = "10") limit: Int
    ): String {
        val result = cypherSearch.query(
            purpose = "list-chunks",
            query = """
                MATCH (c:Chunk)
                RETURN c.id AS id, substring(c.text, 0, 100) AS preview, c.parentId AS parentId
                ORDER BY c.ingestionTimestamp DESC
                LIMIT ${"$"}limit
            """.trimIndent(),
            params = mapOf("limit" to limit)
        )

        if (result.items().isEmpty()) {
            return "No chunks found."
        }

        val sb = StringBuilder("Recent Chunks (limit $limit):\n")
        result.items().forEachIndexed { i, row ->
            val id = row["id"] ?: "?"
            val preview = (row["preview"] as? String)?.replace("\n", " ")?.take(80) ?: ""
            sb.appendLine("  ${i + 1}. [$id] $preview...")
        }
        return sb.toString()
    }

    @ShellMethod(value = "List documents (roots)", key = ["list-docs", "ld"])
    fun listDocuments(
        @ShellOption(defaultValue = "10") limit: Int
    ): String {
        val result = cypherSearch.query(
            purpose = "list-documents",
            query = """
                MATCH (d)
                WHERE 'Document' IN labels(d) OR 'ContentRoot' IN labels(d)
                RETURN d.id AS id, d.uri AS uri, d.title AS title
                ORDER BY d.ingestionTimestamp DESC
                LIMIT ${"$"}limit
            """.trimIndent(),
            params = mapOf("limit" to limit)
        )

        if (result.items().isEmpty()) {
            return "No documents found."
        }

        val sb = StringBuilder("Documents (limit $limit):\n")
        result.items().forEachIndexed { i, row ->
            val id = row["id"] ?: "?"
            val uri = row["uri"] ?: "(no uri)"
            val title = row["title"] ?: "(no title)"
            sb.appendLine("  ${i + 1}. [$id] $title")
            sb.appendLine("      URI: $uri")
        }
        return sb.toString()
    }

    @ShellMethod(value = "Execute a raw Cypher query", key = ["query", "q"])
    fun query(cypher: String): String {
        return try {
            val result = cypherSearch.query(
                purpose = "shell-query",
                query = cypher,
                params = emptyMap<String, Any>()
            )
            formatQueryResult(result.items())
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    @ShellMethod(value = "Full-text search on chunks", key = ["fulltext-search", "fts"])
    fun fullTextSearch(
        searchText: String,
        @ShellOption(defaultValue = "10") limit: Int
    ): String {
        return try {
            val result = cypherSearch.query(
                purpose = "fulltext-search",
                query = """
                    CALL db.index.fulltext.queryNodes(${"$"}indexName, ${"$"}searchText)
                    YIELD node, score
                    WHERE 'Chunk' IN labels(node)
                    RETURN node.id AS id, score, substring(node.text, 0, 150) AS preview
                    LIMIT ${"$"}limit
                """.trimIndent(),
                params = mapOf(
                    "indexName" to properties.contentElementFullTextIndex,
                    "searchText" to searchText,
                    "limit" to limit
                )
            )

            if (result.items().isEmpty()) {
                return "No results found for: $searchText"
            }

            val sb = StringBuilder("Full-text search results for '$searchText':\n")
            result.items().forEachIndexed { i, row ->
                val id = row["id"] ?: "?"
                val score = row["score"] ?: 0.0
                val preview = (row["preview"] as? String)?.replace("\n", " ") ?: ""
                sb.appendLine("  ${i + 1}. [score: ${"%.3f".format(score)}] $id")
                sb.appendLine("      $preview...")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error: ${e.message}\nNote: Full-text index '${properties.contentElementFullTextIndex}' may not exist."
        }
    }

    @ShellMethod(value = "Get a chunk by ID", key = ["get-chunk", "gc"])
    fun getChunk(id: String): String {
        val result = cypherSearch.query(
            purpose = "get-chunk",
            query = """
                MATCH (c:Chunk {id: ${"$"}id})
                RETURN c.id AS id, c.text AS text, c.parentId AS parentId, labels(c) AS labels
            """.trimIndent(),
            params = mapOf("id" to id)
        )

        val row = result.singleOrNull() ?: return "Chunk not found: $id"
        val text = row["text"] as? String ?: "(no text)"
        val parentId = row["parentId"] ?: "(no parent)"
        val labels = row["labels"] ?: emptyList<String>()

        return """
            |Chunk: $id
            |Labels: $labels
            |Parent: $parentId
            |
            |Text:
            |$text
        """.trimMargin()
    }

    @ShellMethod(value = "Show database schema (labels and relationships)", key = ["schema"])
    fun schema(): String {
        val labels = cypherSearch.query(
            purpose = "schema-labels",
            query = "CALL db.labels() YIELD label RETURN label ORDER BY label",
            params = emptyMap<String, Any>()
        )
        val rels = cypherSearch.query(
            purpose = "schema-rels",
            query = "CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType ORDER BY relationshipType",
            params = emptyMap<String, Any>()
        )
        val indexes = cypherSearch.query(
            purpose = "schema-indexes",
            query = "SHOW INDEXES YIELD name, type, labelsOrTypes, properties RETURN name, type, labelsOrTypes, properties",
            params = emptyMap<String, Any>()
        )

        val sb = StringBuilder()
        sb.appendLine("Labels:")
        labels.items().forEach { sb.appendLine("  - ${it["label"]}") }
        sb.appendLine("\nRelationship Types:")
        rels.items().forEach { sb.appendLine("  - ${it["relationshipType"]}") }
        sb.appendLine("\nIndexes:")
        indexes.items().forEach { row ->
            sb.appendLine("  - ${row["name"]} (${row["type"]}): ${row["labelsOrTypes"]} ${row["properties"]}")
        }
        return sb.toString()
    }

    @ShellMethod(value = "Check if embeddings exist on chunks", key = ["check-embeddings", "cem"])
    fun checkEmbeddings(): String {
        val withEmbedding = cypherSearch.queryForInt(
            "MATCH (c:Chunk) WHERE c.embedding IS NOT NULL RETURN count(c) AS count"
        )
        val withoutEmbedding = cypherSearch.queryForInt(
            "MATCH (c:Chunk) WHERE c.embedding IS NULL RETURN count(c) AS count"
        )
        return """
            |Embedding Status:
            |  Chunks with embeddings: $withEmbedding
            |  Chunks without embeddings: $withoutEmbedding
        """.trimMargin()
    }

    private fun formatQueryResult(items: List<Map<String, Any>>): String {
        if (items.isEmpty()) {
            return "(no results)"
        }
        val sb = StringBuilder()
        sb.appendLine("Results (${items.size} rows):")
        items.forEachIndexed { i, row ->
            sb.appendLine("  Row ${i + 1}:")
            row.forEach { (k, v) ->
                val valueStr = when (v) {
                    is FloatArray -> "[${v.size} floats]"
                    is List<*> -> if (v.size > 5) "[${v.size} items]" else v.toString()
                    is String -> if (v.length > 100) "${v.take(100)}..." else v
                    else -> v.toString()
                }
                sb.appendLine("    $k: $valueStr")
            }
        }
        return sb.toString()
    }
}
