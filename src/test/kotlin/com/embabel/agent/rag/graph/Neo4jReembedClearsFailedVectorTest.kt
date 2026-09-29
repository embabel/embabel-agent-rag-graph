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
import com.embabel.agent.rag.store.EmbeddingIncompleteException
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.load
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

private const val REJECTED_MARKER = "rejected-by-the-embedder"

/**
 * A re-embed saves a chunk it could not embed with no vector. [ReembedSurvivesAFailedBatchTest] checks
 * what is passed to the object manager; this checks what the store holds afterwards, because Drivine's
 * default save skips null fields and would leave the previous model's vector in place.
 */
@SpringBootTest(classes = [Neo4jReembedClearsFailedVectorTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jReembedClearsFailedVectorTest {

    /** Rejects any call containing a text with [REJECTED_MARKER], as a model does with an oversized input. */
    class Rejecting(private val delegate: EmbeddingService) : EmbeddingService by delegate {
        override fun embed(text: String): FloatArray = embed(listOf(text)).single()
        override fun embed(texts: List<String>): List<FloatArray> {
            require(texts.none { REJECTED_MARKER in it }) { "input exceeds the model's token limit" }
            return delegate.embed(texts)
        }
    }

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
        fun embeddingService(): EmbeddingService =
            Rejecting(SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()))

        @Bean
        fun gomStore(
            factory: GraphObjectManagerFactory,
            pm: PersistenceManager,
            properties: GraphRagServiceProperties,
            embeddingService: EmbeddingService,
        ): GraphObjectManagerStore = GraphObjectManagerStore(
            gom = factory.get("graph"),
            persistenceManager = pm,
            properties = properties,
            chunkerConfig = ContentChunker.Config(),
            chunkTransformer = ChunkTransformer.NO_OP,
            embeddingService = embeddingService,
        )
    }

    @Autowired lateinit var store: GraphObjectManagerStore
    @Autowired @Qualifier("graph") lateinit var pm: PersistenceManager
    @Autowired lateinit var gom: GraphObjectManager
    @Autowired lateinit var embeddingService: EmbeddingService

    private lateinit var prefix: String
    private val goodId get() = "$prefix-good"
    private val badId get() = "$prefix-bad"

    @BeforeEach
    fun setUp() {
        prefix = "reembed-${UUID.randomUUID()}"
        store.provision()
    }

    @AfterEach
    fun cleanUp() {
        pm.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.id STARTS WITH \$prefix DETACH DELETE n")
                .bind(mapOf("prefix" to prefix)),
        )
    }

    /** A chunk carrying a vector from a previous model: every component 1. */
    private fun seed(id: String, text: String) {
        val stale = List(embeddingService.dimensions) { 1f }
        gom.save(ChunkNode(id = id, text = text, urtext = text, parentId = "$prefix-parent", embedding = stale))
        assertNotNull(gom.load<ChunkNode>(id)?.embedding, "precondition: stale vector persisted")
    }

    @Test
    fun `a chunk that cannot be embedded is left with no vector, and the others get new ones`() {
        seed(goodId, "graph databases and vector search")
        seed(badId, "an oversized chunk $REJECTED_MARKER")

        val e = assertThrows<EmbeddingIncompleteException> { store.reembedAll() }

        assertTrue(badId in e.missingChunkIds)
        assertTrue(gom.load<ChunkNode>(badId)?.embedding == null, "an old-model vector must not survive a failed re-embed")
        val good = gom.load<ChunkNode>(goodId)?.embedding
        assertNotNull(good)
        assertNotEquals(List(embeddingService.dimensions) { 1f }, good, "the embedded chunk has a new vector")
    }
}
