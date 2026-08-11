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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.graph.fulltext.FullTextQueryMode
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Wiring proof for the **entity** full-text path.
 *
 * The rewrite itself is covered exhaustively by
 * [com.embabel.agent.rag.graph.fulltext.FullTextQueryPreparationTest], and its retrieval effect
 * end-to-end by [Neo4jPrecisionRetrievalTest] on the chunk path. What is left to establish is that
 * the entity path is actually connected to it — that the query bound into
 * `named_entity_fulltext_search` is the *rewritten* one, and that the fallback re-issues the
 * original. Capturing the bound parameters proves exactly that, without standing up an entity
 * corpus and index whose behaviour would only re-test Lucene.
 *
 * An entity named by an identifier — a part number, an account reference, a ticket id — is the same
 * lookup a vector index cannot serve, so it must not be left behind while chunk search gets precise.
 */
class EntityFullTextRequiredTermsTest {

    private val properties = GraphRagServiceProperties().apply { queryMode = FullTextQueryMode.LITERAL }

    /** Captures every `searchText` bound to the entity full-text query, in call order. */
    private fun repositoryCapturing(
        boundQueries: MutableList<String>,
        results: (String) -> List<SimilarityResult<NamedEntityData>>,
    ): DrivineNamedEntityDataRepository {
        val pm = mockk<PersistenceManager>(relaxed = true)
        val spec = slot<QuerySpecification<SimilarityResult<NamedEntityData>>>()
        every { pm.query(capture(spec)) } answers {
            val searchText = spec.captured.parameters["searchText"] as String
            boundQueries += searchText
            results(searchText)
        }
        return DrivineNamedEntityDataRepository(
            persistenceManager = pm,
            properties = properties,
            dataDictionary = mockk<DataDictionary>(relaxed = true),
            embeddingService = mockk<EmbeddingService>(relaxed = true),
            verifyIndexes = false,
        )
    }

    private fun search(repo: DrivineNamedEntityDataRepository, query: String) =
        repo.textSearch(TextSimilaritySearchRequest(query, 0.0, 10), null, null)

    @Test
    @DisplayName("the identifier reaches the entity query as a required term")
    fun `identifier is required in the bound entity query`() {
        val bound = mutableListOf<String>()
        val repo = repositoryCapturing(bound) { listOf(mockk(relaxed = true)) }

        search(repo, "which account owns PN-88421-C")

        assertEquals(1, bound.size, "a matching required-term query needs no second search")
        assertTrue(
            // Escaped, not quoted: LITERAL escapes every special character, so the hyphens arrive
            // as `\-` and the parser reads one term rather than three.
            bound.single().startsWith("+PN\\-88421\\-C"),
            "entity search must receive the rewritten query; got '${bound.single()}'",
        )
    }

    @Test
    @DisplayName("an unmatched required term falls back to the original entity query")
    fun `fallback re-issues the original query`() {
        val bound = mutableListOf<String>()
        // Nothing matches the required form; the fallback must run the user's original words.
        val repo = repositoryCapturing(bound) { if (it.startsWith("+")) emptyList() else listOf(mockk(relaxed = true)) }

        val results = search(repo, "which account owns PN-99999-Z")

        assertEquals(2, bound.size, "expected a required-term attempt then a fallback; got $bound")
        assertTrue(bound[0].startsWith("+PN\\-99999\\-Z"), "first attempt requires the identifier")
        assertEquals(
            "which account owns PN\\-99999\\-Z", bound[1],
            "fallback drops the requirement but keeps the escaping — it is still LITERAL",
        )
        assertTrue(results.isNotEmpty(), "fallback results must be returned, not discarded")
    }

    @Test
    @DisplayName("a query with no identifier is passed through untouched and searched once")
    fun `prose entity query is unchanged`() {
        val bound = mutableListOf<String>()
        val repo = repositoryCapturing(bound) { emptyList() }

        search(repo, "accounts in arrears")

        assertEquals(listOf("accounts in arrears"), bound, "no rewrite, and no wasted second search")
    }

    @Test
    @DisplayName("EXPRESSION mode leaves the entity query exactly as the caller wrote it")
    fun `EXPRESSION mode passes the entity query through untouched`() {
        properties.queryMode = FullTextQueryMode.EXPRESSION
        val bound = mutableListOf<String>()
        val repo = repositoryCapturing(bound) { emptyList() }

        search(repo, "which account owns PN-88421-C")

        assertEquals(listOf("which account owns PN-88421-C"), bound)
        assertFalse(bound.single().contains('+'), "no required-term operator when disabled")
    }
}
