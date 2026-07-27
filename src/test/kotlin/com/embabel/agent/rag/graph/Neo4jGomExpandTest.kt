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
import com.embabel.agent.rag.graph.model.LeafSectionNode
import com.embabel.agent.rag.graph.test.DeterministicEmbeddingModel
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.service.ResultExpander
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
 * Runtime proof of the GOM store's [ResultExpander] built on the generated query DSL: `expand` walks
 * `NEXT_CHUNK` with a query-time `depth()` window, `zoomOut` follows `HAS_PARENT` one hop — both typed,
 * both anchored on the root via `where { … }`.
 */
@SpringBootTest(classes = [Neo4jGomExpandTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jGomExpandTest {

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
        prefix = "ex-${UUID.randomUUID()}"
    }

    @AfterEach
    fun cleanUp() {
        pm.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.id STARTS WITH \$p DETACH DELETE n").bind(mapOf("p" to prefix))
        )
    }

    @Test
    fun `expand walks NEXT_CHUNK with a query-time depth window`() {
        // 5 chunks in one section, sequence 0..4; NEXT_CHUNK chain between consecutive.
        (0..4).forEach {
            gom.save(
                ChunkNode(
                    id = id("c$it"), text = "chunk $it", urtext = "chunk $it", parentId = id("sec"),
                    containerSectionId = id("sec"), sequenceNumber = it.toLong(),
                )
            )
        }
        pm.execute(
            QuerySpecification.withStatement(
                """
                UNWIND [[${'$'}c0,${'$'}c1],[${'$'}c1,${'$'}c2],[${'$'}c2,${'$'}c3],[${'$'}c3,${'$'}c4]] AS pr
                MATCH (a:Chunk {id: pr[0]}), (b:Chunk {id: pr[1]}) MERGE (a)-[:NEXT_CHUNK]->(b)
                """.trimIndent()
            ).bind((0..4).associate { "c$it" to id("c$it") })
        )

        // anchor c2, window ±1 → c1, c2, c3 in order
        val window = store.expandResult(id("c2"), ResultExpander.Method.SEQUENCE, 1).map { it.id }
        assertEquals(listOf(id("c1"), id("c2"), id("c3")), window, "±1 window around c2")

        // window ±2 → c0..c4
        val wider = store.expandResult(id("c2"), ResultExpander.Method.SEQUENCE, 2).map { it.id }
        assertEquals((0..4).map { id("c$it") }, wider, "±2 window covers the chain")
    }

    @Test
    fun `zoomOut follows HAS_PARENT one hop to the parent`() {
        gom.save(LeafSectionNode(id = id("sec"), title = "S", text = "s", parentId = id("doc")))
        gom.save(ChunkNode(id = id("c0"), text = "c0", urtext = "c0", parentId = id("sec")))
        pm.execute(
            QuerySpecification.withStatement(
                "MATCH (c:ContentElement {id: \$c}), (p:ContentElement {id: \$s}) MERGE (c)-[:HAS_PARENT]->(p)"
            ).bind(mapOf("c" to id("c0"), "s" to id("sec")))
        )

        val parents = store.expandResult(id("c0"), ResultExpander.Method.ZOOM_OUT, 1).map { it.id }
        assertEquals(listOf(id("sec")), parents, "zoomOut returns the parent section")
    }
}
