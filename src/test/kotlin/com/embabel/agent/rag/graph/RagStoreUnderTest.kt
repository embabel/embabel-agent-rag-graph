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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest

/**
 * The minimal store surface the cross-engine characterization spec exercises, so the *same* spec can
 * be run against more than one implementation (the current [DrivineStore] and the
 * [GraphObjectManager][org.drivine.manager.GraphObjectManager]-backed store) — an A/B equivalence
 * check. Chunk-focused because that is all the retrieval spec needs.
 */
interface RagStoreUnderTest {
    fun provision()
    fun writeAndChunkDocument(doc: NavigableDocument): List<String>
    fun findAllChunksById(ids: List<String>): List<Chunk>
    fun textSearchChunks(query: String, topK: Int = 10): List<SimilarityResult<out Chunk>>
    fun vectorSearchChunks(query: String, topK: Int = 10): List<SimilarityResult<out Chunk>>
    fun reembedAll(): ReembedReport
}

/** Adapts the current hand-rolled [DrivineStore] to [RagStoreUnderTest]. */
class DrivineRagStoreAdapter(private val store: DrivineStore) : RagStoreUnderTest {
    override fun provision() = store.provision()
    override fun writeAndChunkDocument(doc: NavigableDocument): List<String> = store.writeAndChunkDocument(doc)
    override fun findAllChunksById(ids: List<String>): List<Chunk> = store.findAllChunksById(ids).toList()
    override fun textSearchChunks(query: String, topK: Int): List<SimilarityResult<out Chunk>> =
        store.textSearch(TextSimilaritySearchRequest(query, 0.0, topK), Chunk::class.java)

    override fun vectorSearchChunks(query: String, topK: Int): List<SimilarityResult<out Chunk>> =
        store.vectorSearch(TextSimilaritySearchRequest(query, 0.0, topK), Chunk::class.java)

    override fun reembedAll(): ReembedReport = store.reembedAll()
}

/** Adapts the [GraphObjectManager][org.drivine.manager.GraphObjectManager]-backed store to [RagStoreUnderTest]. */
class GomRagStoreAdapter(private val store: GraphObjectManagerStore) : RagStoreUnderTest {
    override fun provision() = store.provision()
    override fun writeAndChunkDocument(doc: NavigableDocument): List<String> = store.writeAndChunkDocument(doc)
    override fun findAllChunksById(ids: List<String>): List<Chunk> = store.findAllChunksById(ids).toList()
    override fun textSearchChunks(query: String, topK: Int): List<SimilarityResult<out Chunk>> =
        store.textSearch(TextSimilaritySearchRequest(query, 0.0, topK), Chunk::class.java)

    override fun vectorSearchChunks(query: String, topK: Int): List<SimilarityResult<out Chunk>> =
        store.vectorSearch(TextSimilaritySearchRequest(query, 0.0, topK), Chunk::class.java)

    override fun reembedAll(): ReembedReport = store.reembedAll()
}
