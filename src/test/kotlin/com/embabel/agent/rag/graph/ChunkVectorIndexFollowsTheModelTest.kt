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

import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.schema.EnsureResult
import org.drivine.schema.IndexManager
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

/**
 * The chunk vector index must describe what the CURRENT model produces.
 *
 * This store built its spec with `by lazy`, so the width was fixed at the first provisioning and
 * kept for the life of the process. `reembedAll` then dropped the index by identity (which ignores
 * width, so that worked), rewrote every chunk at the new model's width, and remade the index from
 * the cached spec — at the OLD width.
 *
 * The result was chunks holding 3072-wide vectors under an index declaring 1536, with nothing
 * reporting it, observed on a live appliance changing text-embedding-3-small to
 * text-embedding-3-large.
 */
class ChunkVectorIndexFollowsTheModelTest {

    /** Reports whatever width it is currently told to, as a model swapped underneath would. */
    private class SwappableEmbedding(var width: Int) : EmbeddingService {
        override val name = "swappable"
        override val provider = "test"
        override val pricingModel: PricingModel? = null
        override val dimensions: Int get() = width
        override fun embed(text: String) = FloatArray(width)
        override fun embed(texts: List<String>) = texts.map { embed(it) }
    }

    private fun store(embedding: EmbeddingService, persistence: PersistenceManager) =
        GraphObjectManagerStore(
            gom = mockk<GraphObjectManager>(relaxed = true),
            persistenceManager = persistence,
            properties = GraphRagServiceProperties(),
            chunkerConfig = ContentChunker.Config(),
            chunkTransformer = mockk<ChunkTransformer>(relaxed = true),
            embeddingService = embedding,
        )

    @Test
    @DisplayName("provisioning after a model change declares the new width, not the first one")
    fun `the spec is not cached across a model change`() {
        val indexes = mockk<IndexManager>(relaxed = true)
        val persistence = mockk<PersistenceManager>(relaxed = true)
        every { persistence.indexes } returns indexes
        // Answer as the real manager does, so GraphProvisioner's convention-name check has an
        // info to read rather than a relaxed mock's null.
        every { indexes.ensure(any()) } answers { EnsureResult.Created(SchemaItemInfo.fromSpec(firstArg())) }
        val embedding = SwappableEmbedding(1536)

        val store = store(embedding, persistence)
        store.provision()

        // The model is swapped underneath, exactly as SwitchableEmbeddingService does on a reindex.
        embedding.width = 3072
        store.provision()

        val specs = mutableListOf<VectorIndexSpec>()
        verify { indexes.ensure(capture(specs)) }
        assertEquals(
            listOf(1536, 3072),
            specs.map { it.dimensions },
            "the second provisioning must declare the width the model now produces",
        )
    }
}
