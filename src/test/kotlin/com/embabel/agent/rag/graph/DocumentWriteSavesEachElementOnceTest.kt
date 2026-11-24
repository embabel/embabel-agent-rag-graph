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

import com.embabel.agent.rag.graph.model.ChunkNode
import com.embabel.agent.rag.graph.model.ContainerSectionNode
import com.embabel.agent.rag.graph.model.DocumentNode
import com.embabel.agent.rag.graph.model.LeafSectionNode
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.common.ai.model.EmbeddingService
import io.mockk.every
import io.mockk.mockk
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Ingesting a document writes each of its elements once (embabel-agent#2110).
 *
 * Before, every section was saved alone and then saved again to record its place, and every chunk was saved
 * bare and then again with its vector: seven statements for a one-section code declaration, two of them
 * repeats. Now the sections go in one placed batch per kind, and the chunks only with their vectors.
 */
class DocumentWriteSavesEachElementOnceTest {

    private val gom = mockk<StatelessGraphObjectManager>(relaxed = true)
    private val saved = mutableListOf<Any>()
    private val batches = mutableListOf<List<Any>>()

    private val embeddings = mockk<EmbeddingService>(relaxed = true).also { service ->
        every { service.embed(any<List<String>>()) } answers { firstArg<List<String>>().map { FloatArray(4) { 0.5f } } }
    }

    private val store = GraphObjectManagerStore(
        gom = gom,
        persistenceManager = mockk<PersistenceManager>(relaxed = true),
        properties = GraphRagServiceProperties(),
        chunkerConfig = ContentChunker.Config(maxChunkSize = 200, overlapSize = 20),
        chunkTransformer = ChunkTransformer.NO_OP,
        embeddingService = embeddings,
    )

    init {
        every { gom.save(any<Any>()) } answers { firstArg<Any>().also { saved += it } }
        every { gom.saveAll(any<List<Any>>()) } answers { firstArg<List<Any>>().also { batches += it } }
    }

    private val document = MaterializedDocument(
        id = "doc",
        uri = "code://repo!src/A.kt#class:A",
        title = "A (kotlin class)",
        children = listOf(
            DefaultMaterializedContainerSection(
                id = "class", title = "A", parentId = "doc",
                children = listOf(
                    LeafSection(id = "f", title = "f", text = "fun f(x: Int) = x + 1\n".repeat(12), parentId = "class"),
                    LeafSection(id = "g", title = "g", text = "fun g(y: Int) = y * 2\n".repeat(12), parentId = "class"),
                ),
            ),
        ),
    )

    @Test
    fun `each section is written once, already in its place`() {
        store.writeAndChunkDocument(document)

        assertEquals(listOf(DocumentNode::class), saved.map { it::class }, "only the root goes to save alone")
        val leaves = batches.flatten().filterIsInstance<LeafSectionNode>()
        val containers = batches.flatten().filterIsInstance<ContainerSectionNode>()
        assertEquals(listOf("f", "g"), leaves.map { it.id })
        assertEquals(listOf("class"), containers.map { it.id })
        /* reading order: the class (0) before its methods (1, 2) */
        assertEquals(listOf(0L to 1L), containers.map { it.ordinal to it.depth })
        assertEquals(listOf(1L to 2L, 2L to 2L), leaves.map { it.ordinal to it.depth })
        assertEquals(setOf("doc"), (leaves.map { it.rootDocumentId } + containers.map { it.rootDocumentId }).toSet())
    }

    @Test
    fun `each chunk is written once, with its vector`() {
        val chunkIds = store.writeAndChunkDocument(document)

        val chunkWrites = batches.flatten().filterIsInstance<ChunkNode>()
        assertEquals(chunkIds.sorted(), chunkWrites.map { it.id }.sorted(), "one write per chunk")
        assert(chunkWrites.all { it.embedding != null }) { "every chunk written with its vector" }
        assertEquals(emptyList<ChunkNode>(), saved.filterIsInstance<ChunkNode>(), "no chunk saved alone, bare")
    }
}
