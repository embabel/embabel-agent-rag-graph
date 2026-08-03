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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Cross-engine proof that a [ChunkNode] persists and round-trips through Drivine's
 * [GraphObjectManager] — the storage primitive the GraphObjectManager-backed store will build on.
 *
 * Pins the three things the hand-rolled path did by hand (and the migration must preserve):
 *  - `@GraphProperty` promoted fields are stored **flat under their snake_case on-disk names**
 *    (so the retained traversal Cypher keeps working);
 *  - the `@VectorIndex` embedding is stored so vector search finds it — on FalkorDB that means
 *    `vecf32(...)`, applied automatically by Drivine's save (0.0.61), not by us;
 *  - `@PropertyBag` free-form metadata and the promoted fields both reassemble into [Chunk.metadata].
 */
abstract class AbstractChunkNodePersistenceTest {

    protected abstract val gom: GraphObjectManager
    protected abstract val persistenceManager: PersistenceManager
    protected abstract val engineName: String

    private val tracked = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        tracked.clear()
    }

    @AfterEach
    fun tearDown() {
        if (tracked.isNotEmpty()) {
            persistenceManager.execute(
                QuerySpecification.withStatement("MATCH (n) WHERE n.id IN \$ids DETACH DELETE n")
                    .bind(mapOf("ids" to tracked))
            )
        }
    }

    private fun nodeKeys(id: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        val rows = persistenceManager.query(
            QuerySpecification.withStatement("MATCH (n:Chunk {id: \$id}) RETURN {keys: keys(n)} AS r")
                .bind(mapOf("id" to id))
                .transform(Map::class.java)
        ) as List<Map<String, Any?>>
        return (rows.firstOrNull()?.get("keys") as? List<*>)?.map { it.toString() } ?: emptyList()
    }

    @Test
    fun `stores promoted structural keys flat under their on-disk names`() {
        val id = UUID.randomUUID().toString()
        tracked += id
        val chunk = Chunk.create(
            text = "Retrieval body",
            parentId = "parent-$id",
            metadata = mapOf("container_section_id" to "sec-1", "sequence_number" to 2, "source" to "wiki"),
            id = id,
        )

        gom.save(ChunkNode.from(chunk))

        val keys = nodeKeys(id)
        assertTrue("container_section_id" in keys, "[$engineName] expected flat on-disk key, got $keys")
        assertTrue("sequence_number" in keys, "[$engineName] expected flat on-disk key, got $keys")
        assertTrue("containerSectionId" !in keys, "[$engineName] camelCase field name must not leak on-disk")
        // free-form metadata lives under the metadata. prefix
        assertTrue(keys.any { it == "metadata.source" }, "[$engineName] free-form metadata should be bagged, got $keys")
    }

    @Test
    fun `round-trips a chunk with structure restored`() {
        val id = UUID.randomUUID().toString()
        tracked += id
        val chunk = Chunk.create(
            text = "Retrieval body",
            parentId = "parent-$id",
            metadata = mapOf("container_section_id" to "sec-1", "sequence_number" to 2, "source" to "wiki"),
            id = id,
        )
        gom.save(ChunkNode.from(chunk))

        val loaded = gom.load(id, ChunkNode::class.java)
        assertNotNull(loaded, "[$engineName] chunk should load back")
        val restored = loaded!!.toCoreType()
        assertEquals("Retrieval body", restored.text)
        // structure survives the DB round trip as typed fields (the engine's Long widens back to Int)
        assertEquals("sec-1", restored.structure.containerSectionId)
        assertEquals(2, restored.structure.sequenceNumber)
        assertEquals(chunk.structure, restored.structure)
        assertEquals("wiki", restored.metadata["source"])
    }

    @Test
    fun `polymorphic load dispatches to the right model by label`() {
        val chunkId = UUID.randomUUID().toString()
        val sectionId = UUID.randomUUID().toString()
        val docId = UUID.randomUUID().toString()
        tracked += listOf(chunkId, sectionId, docId)

        gom.save(ChunkNode(id = chunkId, text = "c", urtext = "c", parentId = "p"))
        gom.save(LeafSectionNode(id = sectionId, title = "t", text = "s"))
        gom.save(DocumentNode(id = docId, uri = "u-$docId", title = "d"))

        assertTrue(gom.load(chunkId, ContentElementNode::class.java)?.toCoreType() is Chunk, "[$engineName] chunk")
        assertTrue(gom.load(sectionId, ContentElementNode::class.java)?.toCoreType() is LeafSection, "[$engineName] section")
        assertTrue(
            gom.load(docId, ContentElementNode::class.java)?.toCoreType() is MaterializedDocument,
            "[$engineName] document",
        )
    }
}
