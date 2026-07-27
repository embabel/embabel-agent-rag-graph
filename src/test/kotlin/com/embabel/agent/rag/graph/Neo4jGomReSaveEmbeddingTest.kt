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
import com.embabel.agent.rag.model.Chunk
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * Regression test for the null-embedding clobber: a structure-only re-save through the public
 * [GraphObjectManagerStore.save] path builds a fresh [ChunkNode] whose `embedding` is null (the vector
 * is written separately by `persistChunksWithEmbeddings`). Drivine's default save is a merge-patch
 * (`NullPolicy.IGNORE`, 0.0.73), so it must leave the stored vector untouched rather than clear it.
 *
 * The characterization spec never re-saves a chunk, so it can't catch this — hence a dedicated test.
 */
@SpringBootTest(classes = [Neo4jGomReSaveEmbeddingTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jGomReSaveEmbeddingTest {

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
        fun embeddingService() = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel())

        @Bean
        fun gomStore(
            factory: GraphObjectManagerFactory,
            pm: PersistenceManager,
            properties: GraphRagServiceProperties,
            embeddingService: SpringAiEmbeddingService,
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
    @Autowired lateinit var embeddingService: SpringAiEmbeddingService

    private lateinit var chunkId: String

    @BeforeEach
    fun setUp() {
        chunkId = "resave-${UUID.randomUUID()}"
        store.provision()
    }

    @AfterEach
    fun cleanUp() {
        pm.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.id = \$id DETACH DELETE n").bind(mapOf("id" to chunkId)),
        )
    }

    @Test
    fun `re-saving a chunk preserves its stored embedding`() {
        val text = "graph databases and vector search"
        // Seed an embedded chunk, exactly as persistChunksWithEmbeddings would.
        gom.save(
            ChunkNode(
                id = chunkId,
                text = text,
                urtext = text,
                parentId = "$chunkId-parent",
                embedding = embeddingService.embed(text).toList(),
            ),
        )
        val seeded = gom.load<ChunkNode>(chunkId)?.embedding
        assertNotNull(seeded, "precondition: embedding was persisted")

        // Re-save via the public save() path — a fresh ChunkNode with a null embedding, structure/text
        // only. Under NullPolicy.IGNORE this is a merge-patch and must not clear the vector.
        store.save(
            Chunk.create(
                text = "$text (edited)",
                parentId = "$chunkId-parent",
                metadata = emptyMap(),
                id = chunkId,
                urtext = text,
            ),
        )

        val reloaded = gom.load<ChunkNode>(chunkId)
        assertEquals(seeded, reloaded?.embedding, "embedding must survive a structure-only re-save")
        assertEquals("$text (edited)", reloaded?.text, "the non-null text field was still updated")
    }
}
