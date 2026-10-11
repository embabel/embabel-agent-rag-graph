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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.StatelessGraphObjectManager
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
 * A filtered chunk count runs in the database. [ChunkCountStaysInTheDatabaseTest] checks that no chunk
 * is loaded to count; this checks the database count gives the same answer the in-memory one did.
 */
@SpringBootTest(classes = [Neo4jChunkCountTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jChunkCountTest {

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
        fun graphObjectManager(factory: GraphObjectManagerFactory): StatelessGraphObjectManager = factory.stateless("graph")
        @Bean
        fun embeddingService(): EmbeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel())

        @Bean
        fun gomStore(
            factory: GraphObjectManagerFactory,
            pm: PersistenceManager,
            properties: GraphRagServiceProperties,
            embeddingService: EmbeddingService,
        ): GraphObjectManagerStore = GraphObjectManagerStore(
            gom = factory.stateless("graph"),
            persistenceManager = pm,
            properties = properties,
            chunkerConfig = ContentChunker.Config(),
            chunkTransformer = ChunkTransformer.NO_OP,
            embeddingService = embeddingService,
        )
    }

    @Autowired lateinit var store: GraphObjectManagerStore
    @Autowired @Qualifier("graph") lateinit var pm: PersistenceManager
    @Autowired lateinit var gom: StatelessGraphObjectManager

    private lateinit var prefix: String

    @BeforeEach
    fun setUp() {
        prefix = "count-${UUID.randomUUID()}"
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

    private fun seed(count: Int, context: String) = gom.saveAll(
        (1..count).map { i ->
            ChunkNode(
                id = "$prefix-$context-$i",
                text = "Text $i",
                urtext = "Text $i",
                parentId = "$prefix-parent",
                embedding = List(4) { 1f },
                freeFormMetadata = mapOf("context" to "$prefix-$context"),
            )
        },
    )

    @Test
    fun `a chunk count filtered by context counts only that context`() {
        seed(3, "mine")
        seed(5, "theirs")

        assertEquals(3, store.count(Chunk::class.java, PropertyFilter.Eq("context", "$prefix-mine")))
        assertEquals(5, store.count(Chunk::class.java, PropertyFilter.Eq("context", "$prefix-theirs")))
        assertEquals(0, store.count(Chunk::class.java, PropertyFilter.Eq("context", "$prefix-nobody")))
    }

    @Test
    fun `a combined filter is applied in full`() {
        seed(3, "mine")
        seed(5, "theirs")

        val either = PropertyFilter.Or(
            listOf(
                PropertyFilter.Eq("context", "$prefix-mine"),
                PropertyFilter.Eq("context", "$prefix-theirs"),
            ),
        )

        assertEquals(8, store.count(Chunk::class.java, either))
    }
}
