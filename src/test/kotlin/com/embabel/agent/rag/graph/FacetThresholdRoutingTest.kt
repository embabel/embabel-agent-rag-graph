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
import com.embabel.agent.rag.graph.test.DeterministicEmbeddingModel
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.service.RagRequest
import com.embabel.common.ai.model.SpringAiEmbeddingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `RagRequest` carries **one** `similarityThreshold` and feeds **two** scoring systems. Since
 * full-text scores became absolute (`s/(s+k)`, bounded strictly below 1), a cosine-calibrated
 * threshold applied to that branch rejects everything — and 0.8 is the `RagRequest` default.
 *
 * This asserts the routing directly, by capturing the threshold each branch receives. An end-to-end
 * assertion cannot do it: `search` merges vector and full-text results, so a chunk returned by the
 * vector branch satisfies any "did I get my chunk back" check regardless of what full-text was
 * given. Two such tests were written first and passed with the fix reverted — capturing the
 * arguments is the only form that actually fails when the routing is wrong.
 *
 * The measurements behind the asymmetry are on
 * [com.embabel.agent.rag.graph.fulltext.FULL_TEXT_SIMILARITY_FLOOR].
 */
class FacetThresholdRoutingTest {

    private val fullTextThreshold = slot<Double>()
    private val vectorThreshold = slot<Double>()

    private val gom = mockk<GraphObjectManager>().apply {
        every {
            loadMatching(ChunkNode::class.java, any<String>(), any<Int>(), capture(fullTextThreshold))
        } returns emptyList()
        every {
            loadNearest(ChunkNode::class.java, any<List<Float>>(), any<Int>(), capture(vectorThreshold))
        } returns emptyList()
    }

    private val store = GraphObjectManagerStore(
        gom = gom,
        persistenceManager = mockk<PersistenceManager>(relaxed = true),
        properties = GraphRagServiceProperties(),
        chunkerConfig = ContentChunker.Config(),
        chunkTransformer = ChunkTransformer.NO_OP,
        embeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()),
    )

    @Test
    @DisplayName("the full-text branch does not receive the caller's cosine threshold")
    fun `full text branch gets the full text floor`() {
        store.search(RagRequest(query = "what causes ER20328_23", topK = 10, similarityThreshold = 0.8))

        assertEquals(
            0.0, fullTextThreshold.captured,
            "0.8 is a cosine number; on the full-text scale it rejects every document, including " +
                "exact identifier matches measured at 0.289-0.551",
        )
    }

    @Test
    @DisplayName("the vector branch still receives it, unchanged")
    fun `vector branch keeps the caller threshold`() {
        store.search(RagRequest(query = "what causes ER20328_23", topK = 10, similarityThreshold = 0.8))

        assertEquals(
            0.8, vectorThreshold.captured,
            "0.8 is a defensible high-relevance cut for cosine — unrelated chunk pairs on a real " +
                "corpus have median similarity 0.712, with only 4.7% above 0.8 — so vector keeps it",
        )
    }

    @Test
    @DisplayName("a non-default caller threshold still reaches vector, and still not full-text")
    fun `asymmetry holds for any threshold, not just the default`() {
        store.search(RagRequest(query = "what causes ER20328_23", topK = 10, similarityThreshold = 0.35))

        assertEquals(0.35, vectorThreshold.captured, "vector honours whatever the caller set")
        assertEquals(0.0, fullTextThreshold.captured, "full-text is exempt regardless of the value")
    }
}
