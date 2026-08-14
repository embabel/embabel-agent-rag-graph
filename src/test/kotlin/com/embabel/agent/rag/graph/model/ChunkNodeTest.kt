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
import com.embabel.agent.rag.model.ChunkStructure
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
                "root_document_title" to "Acme 10-K",
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
        assertEquals("Acme 10-K", node.rootDocumentTitle)
        // ...and are NOT duplicated into the bag
        assertTrue("container_section_id" !in node.freeFormMetadata)
        assertTrue("sequence_number" !in node.freeFormMetadata)
        // free-form survives in the bag
        assertEquals("wiki", node.freeFormMetadata["source"])
        assertEquals(listOf(0.1f, 0.2f), node.embedding)
    }

    @Test
    fun `round-trips back into a Chunk with structure restored`() {
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
        // the typed structure is what survives the round trip...
        assertEquals(original.structure, restored.structure)
        assertEquals("sec-1", restored.structure.containerSectionId)
        assertEquals(3, restored.structure.sequenceNumber)
        // ...and free-form metadata is untouched
        assertEquals("wiki", restored.metadata["source"])
        // the compat view still surfaces structural keys, with core's Int typing preserved
        assertEquals("sec-1", restored.metadata["container_section_id"])
        assertEquals(3, restored.metadata["sequence_number"])
    }

    @Test
    fun `maps every ChunkStructure field through the node and back`() {
        val structure = ChunkStructure(
            rootDocumentId = "doc-1",
            rootDocumentTitle = "Acme 10-K",
            containerSectionId = "container-1",
            containerSectionTitle = "Container",
            containerSectionUrl = "http://example.com/container",
            leafSectionId = "leaf-1",
            leafSectionTitle = "Leaf",
            leafSectionUrl = "http://example.com/leaf",
            chunkIndex = 2,
            totalChunks = 7,
            sequenceNumber = 4,
        )
        val chunk = Chunk.create(text = "body", parentId = "p", id = "c", structure = structure)

        // Nothing is dropped: every field a chunker can set has a home on the node.
        assertEquals(structure, ChunkNode.from(chunk).toCoreType().structure)
    }

    @Test
    fun `reads root document title bagged by pre-promotion writes`() {
        // Rows written before root_document_title was promoted have it under the metadata. prefix.
        // Without this fallback, adding the key to ChunkStructure.KEYS would make an existing
        // value vanish: toCoreType passes an explicit structure, so core never reads it back out
        // of the bag, while withoutStructuralKeys now strips it from the bag.
        val node = ChunkNode(
            id = "c",
            text = "body",
            urtext = "body",
            parentId = "p",
            freeFormMetadata = mapOf("root_document_title" to "Acme 10-K"),
        )

        assertEquals("Acme 10-K", node.toCoreType().structure.rootDocumentTitle)
    }

    @Test
    fun `reads container section url bagged by pre-promotion writes`() {
        // Rows written before container_section_url was promoted have it under the metadata. prefix.
        val node = ChunkNode(
            id = "c",
            text = "body",
            urtext = "body",
            parentId = "p",
            freeFormMetadata = mapOf("container_section_url" to "http://example.com/legacy"),
        )

        assertEquals("http://example.com/legacy", node.toCoreType().structure.containerSectionUrl)
    }

    @Test
    fun `maps structure even when metadata no longer exposes structural keys`() {
        // Pins the behaviour core's deprecation window will eventually force: a Chunk whose
        // structural fields are ONLY reachable via structure, never via the metadata map.
        val chunk = StructureOnlyChunk(
            id = "c",
            text = "body",
            urtext = "body",
            parentId = "p",
            metadata = mapOf("source" to "wiki"),
            structure = ChunkStructure(rootDocumentId = "doc-1", sequenceNumber = 9),
        )

        val node = ChunkNode.from(chunk)

        assertEquals("doc-1", node.rootDocumentId)
        assertEquals(9L, node.sequenceNumber)
        assertEquals("wiki", node.freeFormMetadata["source"])
    }

    /** A [Chunk] that does not mirror its structure into [Chunk.metadata]. */
    private data class StructureOnlyChunk(
        override val id: String,
        override val text: String,
        override val urtext: String,
        override val parentId: String,
        override val metadata: Map<String, Any?>,
        override val structure: ChunkStructure,
    ) : Chunk {
        override fun withAdditionalMetadata(metadata: Map<String, Any?>): Chunk =
            copy(metadata = this.metadata + metadata)
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
