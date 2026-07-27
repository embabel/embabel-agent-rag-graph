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
 * Both [DrivineStore] and [GraphObjectManagerStore] implement it, so a consumer injects `GraphRagStore`
 * and the concrete implementation is a `@Primary` bean swap — the A/B toggle.
 */
interface GraphRagStore :
    ChunkingContentElementRepository,
    CoreSearchOperations,
    FilteringVectorSearch,
    FilteringTextSearch,
    ResultExpander,
    RagFacetProvider {

    fun reembedAll(): ReembedReport
}
