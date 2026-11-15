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

import com.embabel.agent.rag.graph.test.DeterministicEmbeddingModel
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Assertions.assertTrue
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

/**
 * Every node a document's ingest writes is found by `id` — each save is `MERGE (n:…:ContentElement {id: …})`,
 * each parent link and next-chunk link MATCHes both ends the same way — so the store indexes `id` under the
 * labels those statements name. Without the index each statement scanned its label: one document's ingest cost
 * the size of the whole store, once per section, chunk and link.
 */
@SpringBootTest(classes = [Neo4jContentElementIdIndexTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jContentElementIdIndexTest {

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

    @BeforeEach
    fun setUp() = store.provision()

    private fun indexedIds(): List<String> = pm.query(
        QuerySpecification
            .withStatement(
                "SHOW INDEXES YIELD type, labelsOrTypes, properties " +
                    "WHERE type = 'RANGE' AND properties = ['id'] RETURN labelsOrTypes[0] AS label",
            )
            .transform(String::class.java),
    )

    @Test
    fun `provisioning indexes id under ContentElement and Chunk`() {
        val labels = indexedIds()
        assertTrue("ContentElement" in labels, "ContentElement(id) range index, got $labels")
        assertTrue("Chunk" in labels, "Chunk(id) range index, got $labels")
    }

    @Test
    fun `a store whose ids are already uniquely constrained provisions without touching the constraint`() {
        pm.execute(QuerySpecification.withStatement("DROP INDEX Chunk_id_range IF EXISTS"))
        pm.execute(
            QuerySpecification.withStatement("CREATE CONSTRAINT chunk_id_unique IF NOT EXISTS FOR (c:Chunk) REQUIRE c.id IS UNIQUE"),
        )
        try {
            store.provision()
            val constraints = pm.query(
                QuerySpecification.withStatement("SHOW CONSTRAINTS YIELD name RETURN name").transform(String::class.java),
            )
            assertTrue("chunk_id_unique" in constraints, "the constraint survives provisioning, got $constraints")
        } finally {
            pm.execute(QuerySpecification.withStatement("DROP CONSTRAINT chunk_id_unique IF EXISTS"))
        }
    }
}
