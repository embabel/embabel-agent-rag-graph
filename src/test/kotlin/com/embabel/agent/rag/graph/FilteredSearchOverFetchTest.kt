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
import com.embabel.agent.rag.graph.test.DeterministicEmbeddingModel
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.ai.model.SpringAiEmbeddingService
import io.mockk.every
import io.mockk.mockk
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The filtered vector path over-fetches; the unfiltered one does not.
 *
 * `k` handed to a vector index is the HNSW beam width, and `where { }` applies after the index
 * yields — so a scoped caller asking for `topK` receives roughly `topK × selectivity` rows, drawn
 * from the globally-nearest rather than the nearest in scope. Measured against a live index in
 * [Neo4jVectorRecallSearchKTest]: 25 rows of a requested 40, holding 25 of the true scoped top 40.
 *
 * That measurement is the *why*; this is the *policy*. It lives in this store rather than in Drivine,
 * which will not infer a cost/correctness tradeoff from a selectivity estimate it does not have.
 *
 * The wiring is asserted by capturing what reaches `gom`, not end-to-end: a stubbed search returns
 * whatever it is told to regardless of `searchK`, so only the captured argument actually fails when
 * the multiplier is not applied.
 */
class FilteredSearchOverFetchTest {

    @Nested
    @DisplayName("the multiplier policy")
    inner class Policy {

        private val properties = GraphRagServiceProperties()

        @Test
        fun `widens the beam by the multiplier`() {
            assertEquals(200, properties.filteredSearchK(40), "40 x the default 5")
        }

        @Test
        fun `caps the beam so the exact re-rank cost stays bounded`() {
            // Drivine re-reads the full embedding property per candidate, so an uncapped multiplier
            // turns a large topK into thousands of vector reads.
            assertEquals(properties.maxFilteredSearchK, properties.filteredSearchK(400))
        }

        @Test
        fun `never asks for fewer rows than the caller wants`() {
            // searchK < topK is rejected outright by Drivine: it could only lose results. A topK at
            // or above the cap therefore does not over-fetch rather than narrowing the beam.
            assertNull(
                properties.filteredSearchK(properties.maxFilteredSearchK),
                "at the cap there is no room to widen, so the search stays untuned",
            )
            assertNull(properties.filteredSearchK(properties.maxFilteredSearchK + 100))
        }

        @Test
        fun `a multiplier of one disables over-fetch entirely`() {
            // Not "multiply by 1" — null, so the emitted Cypher stays byte-identical to what shipped
            // before the knob existed, rather than merely equivalent.
            properties.filteredSearchOverFetch = 1
            assertNull(properties.filteredSearchK(40))
        }
    }

    @Nested
    @DisplayName("what reaches the object manager")
    inner class Wiring {

        /** A list, not a slot: MockK cannot capture a null into a CapturingSlot<Int>. */
        private val searchKs = mutableListOf<Int?>()
        private val properties = GraphRagServiceProperties()

        private val gom = mockk<GraphObjectManager>().apply {
            every {
                loadNearest(
                    ChunkNode::class.java, any<ChunkNodeQueryDsl>(), any<List<Float>>(),
                    any<Int>(), any(), captureNullable(searchKs), any(), any(),
                )
            } returns emptyList()
            every {
                loadNearest(ChunkNode::class.java, any<List<Float>>(), any<Int>(), any(), any(), any())
            } returns emptyList()
        }

        private val store = GraphObjectManagerStore(
            gom = gom,
            persistenceManager = mockk<PersistenceManager>(relaxed = true),
            properties = properties,
            chunkerConfig = ContentChunker.Config(),
            chunkTransformer = ChunkTransformer.NO_OP,
            embeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()),
        )

        @Test
        fun `the filtered path over-fetches`() {
            store.vectorSearchWithFilter(
                TextSimilaritySearchRequest("payment errors", topK = 40, similarityThreshold = 0.8),
                Chunk::class.java,
                PropertyFilter.Eq("source", "wiki"),
                null,
            )

            assertEquals(
                200, searchKs.last(),
                "a scoped caller asking for 40 gets ~25 without a wider beam",
            )
        }

        @Test
        fun `configuring the multiplier off restores the untuned search`() {
            properties.filteredSearchOverFetch = 1

            store.vectorSearchWithFilter(
                TextSimilaritySearchRequest("payment errors", topK = 40, similarityThreshold = 0.8),
                Chunk::class.java,
                PropertyFilter.Eq("source", "wiki"),
                null,
            )

            assertNull(searchKs.last(), "off must mean untuned, not a multiplier of one")
        }
    }
}
