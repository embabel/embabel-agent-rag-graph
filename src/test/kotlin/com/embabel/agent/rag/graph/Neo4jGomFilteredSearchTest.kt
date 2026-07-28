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
import org.junit.jupiter.api.BeforeEach
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
 * Runtime proof of the GOM store's **metadata-filtered full-text search** — the new object-manager path:
 * `textSearchWithFilter` routes through `loadMatching { where { } }` with the embabel [PropertyFilter]
 * translated by [applyFilter]. It now handles a **bagged** `metadata.*` key correctly (flat
 * `` `metadata.source` ``) — the case the old `CypherFilterConverter` got wrong (nested-map access) — plus
 * a promoted **structural** key and `Not`.
 *
 * `vectorSearchWithFilter` is the exact mirror (`loadNearest` filtered form) built on the same
 * [applyFilter] translator; this test drives the full-text path because it's stable on the shared test
 * container (unlike the vector index, which other suites' `reembedAll` drops mid-run).
 */
@SpringBootTest(classes = [Neo4jGomFilteredSearchTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jGomFilteredSearchTest {

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

    private lateinit var prefix: String
    private fun id(s: String) = "$prefix-$s"

    @BeforeEach
    fun setUp() {
        prefix = "flt-${UUID.randomUUID()}"
        store.provision()
        // Three chunks that all match the text query "graph", split by a bagged `source` metadata key and
        // a promoted structural `container_section_id`.
        listOf(
            Triple("a", "wiki", "sec-1"),
            Triple("b", "blog", "sec-1"),
            Triple("c", "wiki", "sec-2"),
        ).forEach { (k, source, section) ->
            gom.save(
                ChunkNode(
                    id = id(k),
                    text = "graph databases chunk $k",
                    urtext = "graph databases chunk $k",
                    parentId = id(section),
                    containerSectionId = id(section),
                    freeFormMetadata = mapOf("source" to source),
                ),
            )
        }
        pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(60)"))
    }

    @AfterEach
    fun cleanUp() {
        pm.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.id STARTS WITH \$p DETACH DELETE n").bind(mapOf("p" to prefix)),
        )
    }

    private fun textIds(filter: PropertyFilter) =
        store.textSearchWithFilter(TextSimilaritySearchRequest("graph", 0.0, 10), Chunk::class.java, filter, null)
            .map { it.match.id }.toSet()

    @Test
    fun `bagged metadata key filters full-text results (the CypherFilterConverter bug fix)`() {
        assertEquals(setOf(id("a"), id("c")), textIds(PropertyFilter.Eq("source", "wiki")), "only source=wiki")
        assertEquals(setOf(id("b")), textIds(PropertyFilter.Eq("source", "blog")), "only source=blog")
    }

    @Test
    fun `structural key filters, and Not negates`() {
        assertEquals(setOf(id("a"), id("b")), textIds(PropertyFilter.Eq("container_section_id", id("sec-1"))), "section 1")
        assertEquals(setOf(id("b")), textIds(PropertyFilter.Not(PropertyFilter.Eq("source", "wiki"))), "NOT source=wiki")
    }

    @Test
    fun `blank full-text query returns empty, not a Lucene ParseException`() {
        assertEquals(
            0,
            store.textSearch(TextSimilaritySearchRequest("", 0.0, 10), Chunk::class.java).size,
            "unfiltered blank query",
        )
        assertEquals(
            0,
            store.textSearchWithFilter(
                TextSimilaritySearchRequest("   ", 0.0, 10), Chunk::class.java, PropertyFilter.Eq("source", "wiki"), null,
            ).size,
            "filtered blank query",
        )
    }
}
