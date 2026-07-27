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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure mapping coverage for [ChunkNode.from] / [ChunkNode.toCoreType] — the domain↔model boundary,
 * independent of any database. The intricate part is that structural keys are promoted to typed
 * fields on the model but must reappear in [Chunk.metadata] on the way back.
 */
class ChunkNodeTest {

    @Test
    fun `promotes structural keys to fields and bags the rest`() {
        val chunk = Chunk.create(
            text = "body",
            parentId = "parent-1",
            metadata = mapOf(
                "container_section_id" to "sec-1",
                "sequence_number" to 3,
                "root_document_id" to "doc-1",
                "source" to "wiki",          // free-form
                "url" to "http://x",         // free-form (also Chunk.uri)
            ),
            id = "chunk-1",
        )

        val node = ChunkNode.from(chunk, embedding = listOf(0.1f, 0.2f))

        // structural keys became typed fields...
        assertEquals("sec-1", node.containerSectionId)
        assertEquals(3L, node.sequenceNumber)          // Int coerced to Long
        assertEquals("doc-1", node.rootDocumentId)
        // ...and are NOT duplicated into the bag
        assertTrue("container_section_id" !in node.freeFormMetadata)
        assertTrue("sequence_number" !in node.freeFormMetadata)
        // free-form survives in the bag
        assertEquals("wiki", node.freeFormMetadata["source"])
        assertEquals(listOf(0.1f, 0.2f), node.embedding)
    }

    @Test
    fun `round-trips back into a Chunk with metadata reassembled`() {
        val original = Chunk.create(
            text = "body",
            parentId = "parent-1",
            metadata = mapOf(
                "container_section_id" to "sec-1",
                "sequence_number" to 3,
                "source" to "wiki",
            ),
            id = "chunk-1",
        )

        val restored = ChunkNode.from(original).toCoreType()

        assertEquals(original.id, restored.id)
        assertEquals(original.text, restored.text)
        assertEquals(original.parentId, restored.parentId)
        // metadata is whole again — structural keys back alongside free-form
        assertEquals("sec-1", restored.metadata["container_section_id"])
        assertEquals(3L, restored.metadata["sequence_number"])
        assertEquals("wiki", restored.metadata["source"])
    }

    @Test
    fun `absent structural keys stay null and out of metadata`() {
        val chunk = Chunk.create(text = "body", parentId = "p", metadata = mapOf("source" to "x"), id = "c")

        val node = ChunkNode.from(chunk)

        assertNull(node.sequenceNumber)
        assertNull(node.containerSectionId)
        assertTrue("sequence_number" !in node.toCoreType().metadata)
        assertEquals("x", node.toCoreType().metadata["source"])
    }

    @Test
    fun `sequence number tolerates string or numeric metadata`() {
        val fromInt = ChunkNode.from(Chunk.create("b", "p", mapOf("sequence_number" to 5), "c"))
        val fromString = ChunkNode.from(Chunk.create("b", "p", mapOf("sequence_number" to "5"), "c"))
        assertEquals(5L, fromInt.sequenceNumber)
        assertEquals(5L, fromString.sequenceNumber)
    }
}
