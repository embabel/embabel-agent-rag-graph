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

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.graph.model.ChunkNode
import com.embabel.agent.rag.graph.model.ChunkNodeQueryDsl
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.ai.model.EmbeddingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Counting chunks must not load them. The interface's default count loaded every chunk in the store,
 * each with its vector and text, and filtered in memory; a document list that showed a chunk total
 * then held the whole corpus on the heap, and on a store of a few thousand chunks the app ran out.
 */
class ChunkCountStaysInTheDatabaseTest {

    private val gom = mockk<StatelessGraphObjectManager>(relaxed = true)

    private val store = GraphObjectManagerStore(
        gom = gom,
        persistenceManager = mockk<PersistenceManager>(relaxed = true),
        properties = GraphRagServiceProperties(),
        chunkerConfig = ContentChunker.Config(),
        chunkTransformer = mockk<ChunkTransformer>(relaxed = true),
        embeddingService = mockk<EmbeddingService>(relaxed = true),
    )

    @Test
    fun `a filtered chunk count asks the database and loads nothing`() {
        every { gom.count(ChunkNode::class.java, any<ChunkNodeQueryDsl>(), any()) } returns 7L

        val count = store.count(Chunk::class.java, PropertyFilter.Eq("context", "mine"))

        assertEquals(7, count)
        verify(exactly = 0) { gom.loadAll(any<Class<Any>>()) }
        verify(exactly = 0) { gom.loadAll(any<Class<Any>>(), any<Any>(), any()) }
    }

    @Test
    fun `an unfiltered chunk count asks the database and loads nothing`() {
        every { gom.count(ChunkNode::class.java, any<ChunkNodeQueryDsl>(), any()) } returns 3L

        assertEquals(3, store.count(Chunk::class.java))
        verify(exactly = 0) { gom.loadAll(any<Class<Any>>()) }
        verify(exactly = 0) { gom.loadAll(any<Class<Any>>(), any<Any>(), any()) }
    }
}
