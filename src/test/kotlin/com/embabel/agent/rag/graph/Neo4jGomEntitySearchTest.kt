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
import com.embabel.common.ai.model.SpringAiEmbeddingService
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
 * `findChunksForEntity` — the entity → chunk traversal (`(entity)<-[:HAS_ENTITY]-(chunk)`). The store
 * doesn't create `HAS_ENTITY` edges itself, so this seeds one to exercise the **non-empty scalar-column**
 * path (`RETURN chunk.id AS id`), which must be read with `queryForScalars` — `queryForRows` chokes on a
 * scalar-column result, so this was a latent crash the moment an entity actually had chunks.
 */
@SpringBootTest(classes = [Neo4jGomEntitySearchTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jGomEntitySearchTest {

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
        prefix = "ent-${UUID.randomUUID()}"
        store.provision()
        gom.save(ChunkNode(id = id("chunk"), text = "graph", urtext = "graph", parentId = id("sec")))
        // (chunk)-[:HAS_ENTITY]->(entity) — the store never writes these, so seed one directly.
        pm.execute(
            QuerySpecification.withStatement(
                "MATCH (chunk:Chunk {id: \$cid}) MERGE (e {id: \$eid}) MERGE (chunk)-[:HAS_ENTITY]->(e)",
            ).bind(mapOf("cid" to id("chunk"), "eid" to id("ent"))),
        )
    }

    @AfterEach
    fun cleanUp() {
        pm.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.id STARTS WITH \$p DETACH DELETE n").bind(mapOf("p" to prefix)),
        )
    }

    @Test
    fun `findChunksForEntity returns linked chunk ids without crashing on the scalar column`() {
        assertEquals(
            setOf(id("chunk")),
            store.findChunksForEntity(id("ent")).map { it.id }.toSet(),
            "the chunk linked via HAS_ENTITY is returned",
        )
    }
}
