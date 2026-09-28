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
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.store.EmbeddingIncompleteException
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.schema.EnsureResult
import org.drivine.schema.IndexManager
import org.drivine.schema.SchemaItemInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/**
 * A re-embed drops the chunk vector index before rewriting every chunk. One failed embedding call
 * used to escape part way and leave the store with no vector index, some chunks on the new model and
 * the rest on the old one, and no record of which.
 */
class ReembedSurvivesAFailedBatchTest {

    private val width = 4

    /** Fails any call that includes one of [rejected], as a model does with an oversized input. */
    private inner class Rejecting(private val rejected: Set<String>) : EmbeddingService {
        val calls = AtomicInteger()
        override val name = "rejecting"
        override val provider = "test"
        override val pricingModel: PricingModel? = null
        override val dimensions: Int get() = width
        override fun embed(text: String) = embed(listOf(text)).single()
        override fun embed(texts: List<String>): List<FloatArray> {
            calls.incrementAndGet()
            require(texts.none { it in rejected }) { "input exceeds the model's token limit" }
            return texts.map { FloatArray(width) { 1f } }
        }
    }

    private val gom = mockk<GraphObjectManager>(relaxed = true)
    private val indexes = mockk<IndexManager>(relaxed = true)
    private val persistence = mockk<PersistenceManager>(relaxed = true).also {
        every { it.indexes } returns indexes
        every { indexes.ensure(any()) } answers { EnsureResult.Created(SchemaItemInfo.fromSpec(firstArg())) }
    }
    private val saved = mutableListOf<ChunkNode>()

    private fun chunks(count: Int) = (1..count).map { i ->
        ChunkNode(id = "c$i", text = "Text $i", urtext = "Text $i", parentId = "p", embedding = listOf(0f, 0f))
    }

    private fun store(embedding: EmbeddingService, nodes: List<ChunkNode>): GraphObjectManagerStore {
        every { gom.loadAll(ChunkNode::class.java) } returns nodes
        every { gom.saveAll(any<List<ChunkNode>>()) } answers {
            saved += firstArg<List<ChunkNode>>()
            firstArg()
        }
        return GraphObjectManagerStore(
            gom = gom,
            persistenceManager = persistence,
            properties = GraphRagServiceProperties(),
            chunkerConfig = ContentChunker.Config(embeddingBatchSize = 4),
            chunkTransformer = mockk<ChunkTransformer>(relaxed = true),
            embeddingService = embedding,
        )
    }

    @Test
    fun `a chunk that cannot be embedded fails the re-embed after the index is rebuilt`() {
        val store = store(Rejecting(setOf("Text 3")), chunks(8))

        val e = assertThrows<EmbeddingIncompleteException> { store.reembedAll() }

        assertEquals(listOf("c3"), e.missingChunkIds)
        assertEquals(7, e.embeddedCount)
        verifyOrder {
            indexes.drop(any())
            indexes.ensure(any())
        }
    }

    @Test
    fun `the other chunks are saved with new vectors and the failed one loses its stale vector`() {
        val store = store(Rejecting(setOf("Text 3")), chunks(8))

        runCatching { store.reembedAll() }

        val byId = saved.associateBy { it.id }
        assertEquals(8, byId.size)
        assertNull(byId.getValue("c3").embedding, "an old-model vector must not sit under the new index")
        assertTrue(byId.filterKeys { it != "c3" }.values.all { it.embedding == List(width) { 1f } })
    }

    @Test
    fun `a dead embedding service stops calling, rebuilds the index and reports every chunk`() {
        val nodes = chunks(600)
        val service = Rejecting(nodes.map { it.text }.toSet())
        val store = store(service, nodes)

        val e = assertThrows<EmbeddingIncompleteException> { store.reembedAll() }

        assertEquals(600, e.missingChunkIds.size)
        assertTrue(service.calls.get() <= 10, "made ${service.calls.get()} calls to a dead service")
        verify { indexes.ensure(any()) }
    }

    @Test
    fun `a clean re-embed reports every chunk and throws nothing`() {
        val store = store(Rejecting(emptySet()), chunks(8))

        val report = store.reembedAll()

        assertEquals(ReembedReport(chunks = 8, entities = 0), report)
    }
}
