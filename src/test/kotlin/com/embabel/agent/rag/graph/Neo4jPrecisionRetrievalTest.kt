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
import com.embabel.agent.rag.graph.fulltext.FullTextQueryMode
import com.embabel.agent.rag.graph.model.ChunkNode
import com.embabel.agent.rag.graph.test.DeterministicEmbeddingModel
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.model.Chunk
import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * End-to-end proof, against a real Lucene-backed Neo4j full-text index, that full-text retrieval
 * finds an identifier the way a user actually asks for one.
 *
 * The corpus is built so that every document shares ordinary vocabulary — `payment`, `service`,
 * `error`, `code` — and exactly one carries the error code `ER20328_23`. A user asking
 * *"what causes error code ER20328_23 in the payment service"* is therefore a worst case for
 * disjunctive matching: every common word drags in the whole corpus, and the identifier that
 * actually identifies the answer contributes no more than any other term.
 *
 * The BM25 score cannot fix that — it sums per-term contributions, so padding an identifier query
 * with ordinary words *raises* the top score while making the result set worse. These tests assert
 * the fix that does work: requiring the identifier, so membership rather than score decides.
 *
 * Each test states the defect it guards, not just "returns something".
 */
@SpringBootTest(classes = [Neo4jPrecisionRetrievalTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jPrecisionRetrievalTest {

    @Configuration
    @Profile("neo4j")
    @EnableDrivine
    @EnableDrivineTestConfig
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableConfigurationProperties(GraphRagServiceProperties::class)
    class Config {
        @Bean("graph")
        fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("graph")

        @Bean
        fun graphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager = factory.get("graph")

        @Bean
        fun gomStore(
            factory: GraphObjectManagerFactory,
            pm: PersistenceManager,
            properties: GraphRagServiceProperties,
        ): GraphObjectManagerStore = GraphObjectManagerStore(
            gom = factory.get("graph"),
            persistenceManager = pm,
            properties = properties,
            chunkerConfig = ContentChunker.Config(),
            chunkTransformer = ChunkTransformer.NO_OP,
            embeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()),
        )
    }

    @Autowired lateinit var store: GraphObjectManagerStore
    @Autowired @Qualifier("graph") lateinit var pm: PersistenceManager
    @Autowired lateinit var gom: GraphObjectManager
    @Autowired lateinit var properties: GraphRagServiceProperties

    private lateinit var prefix: String
    private fun id(s: String) = "$prefix-$s"

    /** The one document that answers the question. */
    private val incident = "incident"

    @BeforeEach
    fun setUp() {
        prefix = "prec-${UUID.randomUUID()}"
        properties.queryMode = FullTextQueryMode.LITERAL
        store.provision()

        save(incident, "the payment service returned error code ER20328_23 during checkout settlement")
        // Distractors: same ordinary vocabulary, no identifier. Under disjunctive matching every one
        // of these is a hit for the user's question.
        save("noise-1", "the payment service logs an error when the code path times out")
        save("noise-2", "error handling in the payment service is described in the service code")
        save("noise-3", "what causes a payment to fail is usually a service error")
        save("noise-4", "the service returned an error code during checkout")
        save("noise-5", "payment settlement runs after the service acknowledges the code")
        save("noise-6", "a checkout error in the payment path returns a service code")
        // A second identifier, so "requires ALL identifiers" is distinguishable from "requires any".
        save("other-incident", "the payment service returned error code ER10044_07 during settlement", source = "docs")

        pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(60)"))
    }

    private fun save(key: String, text: String, source: String = "ops") {
        gom.save(
            ChunkNode(
                id = id(key), text = text, urtext = text,
                parentId = id("sec"), containerSectionId = id("sec"),
                freeFormMetadata = mapOf("source" to source),
            ),
        )
    }

    @AfterEach
    fun cleanUp() {
        properties.queryMode = FullTextQueryMode.LITERAL
        pm.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.id STARTS WITH \$p DETACH DELETE n")
                .bind(mapOf("p" to prefix)),
        )
    }

    /**
     * Lucene indexes populate asynchronously; poll briefly so the assertions are not racing the
     * indexer. Returns the ids of the matching chunks.
     */
    private fun search(query: String, topK: Int = 20, attempts: Int = 40): Set<String> {
        var last = emptySet<String>()
        repeat(attempts) {
            last = store.textSearch(TextSimilaritySearchRequest(query, 0.0, topK), Chunk::class.java)
                .map { it.match.id }.toSet()
            if (last.isNotEmpty()) return last
            Thread.sleep(250)
        }
        return last
    }

    @Nested
    @DisplayName("an identifier asked for in a sentence")
    inner class IdentifierInASentence {

        @Test
        fun `retrieves only the chunk carrying that identifier, not everything sharing a common word`() {
            // THE defect. Without required terms this query matches the whole corpus, because every
            // document contains "payment", "service", "error" or "code". The identifier is the only
            // term that identifies anything, and disjunctive matching gives it no special standing.
            val hits = search("what causes error code ER20328_23 in the payment service")

            assertEquals(
                setOf(id(incident)),
                hits,
                "the identifier should decide membership; got ${hits.size} chunks",
            )
        }

        @Test
        fun `a bare identifier retrieves its chunk`() {
            assertEquals(setOf(id(incident)), search("ER20328_23"))
        }

        @Test
        fun `trailing question punctuation does not become a Lucene wildcard`() {
            // "ER20328_23?" reaching the parser unescaped is a single-character wildcard, which
            // silently changes what matches.
            assertEquals(setOf(id(incident)), search("what causes ER20328_23?"))
        }

        @Test
        fun `requiring every identifier is unsatisfiable here, so the fallback returns both chunks`() {
            // Two identifiers living in different chunks: `+ER20328_23 +ER10044_07` matches nothing,
            // which is the exact case the fallback exists for. The original disjunctive query then
            // returns both incidents — the useful answer to "compare these two codes".
            assertEquals(
                setOf(id(incident), id("other-incident")),
                search("compare ER20328_23 and ER10044_07"),
                "unsatisfiable required terms must degrade to the original query, not to empty",
            )
        }
    }

    @Nested
    @DisplayName("what must not regress")
    inner class NoRegression {

        @Test
        fun `a query with no identifier behaves exactly as before`() {
            // The rewrite must not fire here. Ordinary prose search keeps its recall.
            val hits = search("payment service error")
            assertTrue(
                hits.size > 1,
                "ordinary prose search should still match broadly; got ${hits.size}",
            )
        }

        @Test
        fun `an identifier absent from the corpus falls back rather than returning nothing`() {
            // Deliberate trade: the fallback costs the "correct empty answer" for an unknown code,
            // and buys a guarantee that recall can never regress relative to the previous behaviour.
            // Switch to EXPRESSION to get the caller-composes surface instead.
            val hits = search("what causes error code ER99999_00 in the payment service")
            assertTrue(
                hits.size > 1,
                "an unknown identifier should degrade to the original query, not to empty; got $hits",
            )
        }

        @Test
        fun `EXPRESSION mode passes the query through, so plain words stay disjunctive`() {
            // The before/after, in one assertion pair, on the same corpus and the same query.
            val query = "what causes error code ER20328_23 in the payment service"
            val withRequiredTerms = search(query)

            properties.queryMode = FullTextQueryMode.EXPRESSION
            val withoutRequiredTerms = search(query)

            assertEquals(setOf(id(incident)), withRequiredTerms, "precise under LITERAL")
            assertTrue(
                withoutRequiredTerms.size > withRequiredTerms.size,
                "under EXPRESSION the same plain question drags in the corpus: " +
                    "${withoutRequiredTerms.size} chunks vs ${withRequiredTerms.size}",
            )
        }
    }

    @Nested
    @DisplayName("the metadata-filtered path behaves identically")
    inner class FilteredSearch {

        /**
         * The filtered path is the one production actually uses — every scoped document collection
         * (a user's own uploads, shared docs, mail) searches with a metadata filter attached. A
         * rewrite applied only to the unfiltered path would leave the real path imprecise, so both
         * route through the same helper.
         */
        private fun filteredSearch(query: String, source: String, attempts: Int = 40): Set<String> {
            var last = emptySet<String>()
            repeat(attempts) {
                last = store.textSearchWithFilter(
                    TextSimilaritySearchRequest(query, 0.0, 20),
                    Chunk::class.java,
                    PropertyFilter.Eq("source", source),
                    null,
                ).map { it.match.id }.toSet()
                if (last.isNotEmpty()) return last
                Thread.sleep(250)
            }
            return last
        }

        @Test
        fun `an identifier query with a metadata filter is just as precise as without one`() {
            assertEquals(
                setOf(id(incident)),
                filteredSearch("what causes error code ER20328_23 in the payment service", "ops"),
                "the filter must narrow the set, not coarsen the query",
            )
        }

        @Test
        fun `the metadata filter still excludes chunks the identifier would otherwise match`() {
            // other-incident carries ER10044_07 but source=docs, so an ops-scoped search must miss it
            // even though the identifier matches.
            assertTrue(
                filteredSearch("ER10044_07", "ops").isEmpty(),
                "filter must still apply on top of the required identifier",
            )
            assertEquals(
                setOf(id("other-incident")),
                filteredSearch("ER10044_07", "docs"),
                "and must find it in its own scope",
            )
        }

        @Test
        fun `a filtered prose query keeps its recall`() {
            assertTrue(
                filteredSearch("payment service error", "ops").size > 1,
                "no identifier means no rewrite, filtered or not",
            )
        }
    }

    @Nested
    @DisplayName("optional terms still do their job")
    inner class OptionalTermsRank {

        @Test
        fun `context words rank within the required set rather than widening it`() {
            // Adding context to a code that appears in two chunks must not add chunks — only reorder.
            save("incident-b", "a later checkout also returned ER20328_23 with no settlement impact")
            pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(60)"))

            val bare = search("ER20328_23")
            val withContext = search("ER20328_23 settlement checkout")

            assertEquals(
                bare, withContext,
                "optional terms must not change membership, only ranking",
            )
        }
    }

    @Nested
    @DisplayName("EXPRESSION cannot take retrieval to zero")
    inner class ExpressionNeverGoesDark {

        /**
         * The regression this guards, end to end. A model asked a question containing no identifier
         * required every word of it; the conjunction matched nothing, and because full-text was
         * almost every tool call on that path, the whole retrieval step returned empty.
         *
         * Asserting on the returned CHUNKS rather than on the query string: the unit tests already
         * pin the rewrite, and a string assertion would pass even if the fallback were never issued.
         */
        @Test
        fun `a conjunction that cannot match falls back and still finds the chunk`() {
            properties.queryMode = FullTextQueryMode.EXPRESSION

            // "logs" appears only in a distractor, ER20328_23 only in the incident, so requiring
            // BOTH matches nothing. An earlier version of this test required five words that the
            // incident chunk happens to contain all of — it passed with the fix reverted, proving
            // nothing. The conjunction has to be genuinely unsatisfiable for the fallback to be
            // what recovers the result.
            val hits = search("+ER20328_23 +logs")

            assertEquals(
                setOf(id(incident)), hits,
                "the ordinary word relaxes and the identifier stays required, so the chunk carrying " +
                    "the code is recovered rather than the caller getting nothing",
            )
        }

        @Test
        fun `an absent identifier still returns nothing`() {
            // The other half of the trade. Relaxation must not turn an honest "not found" into junk.
            properties.queryMode = FullTextQueryMode.EXPRESSION

            assertTrue(
                search("+ER99999_00 +payment +service", attempts = 2).isEmpty(),
                "the identifier stays required, so a code absent from the corpus yields no chunks",
            )
        }
    }
}
