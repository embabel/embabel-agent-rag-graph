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
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.agent.rag.service.FilteringTextSearch
import com.embabel.agent.rag.service.FilteringVectorSearch
import com.embabel.agent.rag.service.ResultExpander
import com.embabel.agent.rag.service.support.RagFacetProvider
import com.embabel.agent.rag.store.ChunkingContentElementRepository

/**
 * The full graph-RAG store contract, as a single injectable type.
 *
 * A Kotlin "union" is just an interface that extends the parts — there is no denotable intersection
 * type. This unions the two contracts consumers actually use (ingestion/provisioning via
 * [ChunkingContentElementRepository], and the search surface `ToolishRag` feature-detects —
 * [CoreSearchOperations] + [FilteringVectorSearch] + [FilteringTextSearch] + [ResultExpander] +
 * [RagFacetProvider]), plus the store-level [reembedAll].
 *
 * [GraphObjectManagerStore] implements it; a consumer injects `GraphRagStore` and never binds to
 * the implementation.
 */
interface GraphRagStore :
    ChunkingContentElementRepository,
    CoreSearchOperations,
    FilteringVectorSearch,
    FilteringTextSearch,
    ResultExpander,
    RagFacetProvider {

    /**
     * Re-embed stored content with the current embedding model and rebuild the vector indexes.
     *
     * @throws com.embabel.agent.rag.store.EmbeddingIncompleteException if some chunks could not be
     * embedded. The indexes are rebuilt and the other chunks saved first, and calling this again is a
     * safe retry.
     */
    /** Re-embed everything this store holds with the current model, saying nothing of how far it has got. */
    fun reembedAll(): ReembedReport = reembedAll { _, _ -> }

    /**
     * Re-embed everything this store holds with the current model, and say how far it has got.
     *
     * [onProgress] is called with how many chunks have been gone through and how many there are:
     * once before the first with `(0, total)`, then as the work advances, ending on
     * `(total, total)`. A re-embed is minutes of work on a large store, and a caller with nothing
     * to show for it cannot tell working from stuck. "Gone through" counts a chunk that was
     * skipped or could not be embedded too: it is how far along the walk is, not how many succeeded.
     */
    fun reembedAll(onProgress: (done: Int, total: Int) -> Unit): ReembedReport

    /**
     * The sections of the document at [uri] in reading order: what a table of contents, or a reader
     * paging through the document, walks. [skip] and [limit] take a window of it. Empty when no
     * such document is stored, and for a document ingested before sections were numbered.
     * Headings only; a leaf's text is one [findById] away.
     */
    fun outline(uri: String, skip: Int = 0, limit: Int? = null): List<DocumentSection>

    /**
     * The chunks cut from the section [sectionId], in the order they were cut: what search matches
     * against when it finds that section.
     *
     * A section long enough to be chunked alone has chunks that name it. Short sections are
     * chunked TOGETHER, and such a chunk names only what holds them: the section they sit in, or
     * the document itself when the whole of it is one chunk. So asked for a short leaf this returns
     * nothing, and asked for what holds it, the chunk they share; a caller after a short leaf's
     * chunk asks for the leaf, then for what it sits in, and so on up to the document's id.
     * Empty when [sectionId] names nothing with chunks of either kind.
     */
    fun chunksOf(sectionId: String): List<Chunk>
}
