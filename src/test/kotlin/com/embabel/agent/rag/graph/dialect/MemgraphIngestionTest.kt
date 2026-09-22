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
package com.embabel.agent.rag.graph.dialect

import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.graph.DrivineCypherSearch
import com.embabel.agent.rag.graph.DrivineStore
import com.embabel.agent.rag.graph.GraphRagServiceProperties
import com.embabel.agent.rag.graph.test.FakeEmbeddingModel
import com.embabel.agent.rag.service.ResultExpander
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * Integration tests for Memgraph.
 *
 * Runs against a Memgraph MAGE testcontainer (includes vector search + text search).
 */
@SpringBootTest(classes = [MemgraphIngestionTest.Config::class])
@Testcontainers
@ActiveProfiles("memgraph")
class MemgraphIngestionTest {

    companion object {
        @Container
        @JvmStatic
        val memgraph: GenericContainer<*> = GenericContainer(
            DockerImageName.parse("memgraph/memgraph-mage:latest")
        )
            .withExposedPorts(7687)
            .withCommand("--also-log-to-stderr", "--log-level=WARNING")
    }

    @Configuration
    @Profile("memgraph")
    @EnableDrivine
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableConfigurationProperties(GraphRagServiceProperties::class)
    class Config {

        @Bean
        @Primary
        fun dataSourceMap(): DataSourceMap {
            val props = ConnectionProperties(
                host = memgraph.host,
                port = memgraph.getMappedPort(7687),
                userName = "",
                password = "",
                type = DatabaseType.MEMGRAPH,
                databaseName = "memgraph",
            )
            return DataSourceMap(mapOf("graph" to props))
        }

        @Bean("graph")
        fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager {
            return factory.get("graph")
        }

        @Bean
        fun drivineCypherSearch(persistenceManager: PersistenceManager): DrivineCypherSearch {
            return DrivineCypherSearch(persistenceManager)
        }

        @Bean
        fun drivineStore(
            persistenceManager: PersistenceManager,
            properties: GraphRagServiceProperties,
            transactionManager: PlatformTransactionManager,
            cypherSearch: DrivineCypherSearch,
        ): DrivineStore {
            return DrivineStore(
                persistenceManager = persistenceManager,
                properties = properties,
                chunkerConfig = ContentChunker.Config(),
                chunkTransformer = ChunkTransformer.NO_OP,
                platformTransactionManager = transactionManager,
                cypherSearch = cypherSearch,
                dialect = MemgraphRagDialect(),
                embeddingService = SpringAiEmbeddingService("fake", "embabel", FakeEmbeddingModel()),
            )
        }
    }

    @Autowired
    lateinit var drivineStore: DrivineStore

    @Autowired
    @Qualifier("graph")
    lateinit var persistenceManager: PersistenceManager

    private val testNodeIds = mutableListOf<String>()
    private val logger = LoggerFactory.getLogger(MemgraphIngestionTest::class.java)

    @BeforeEach
    fun setUp() {
        testNodeIds.clear()
    }

    @AfterEach
    fun tearDown() {
        if (testNodeIds.isNotEmpty()) {
            persistenceManager.execute(
                QuerySpecification.withStatement("MATCH (n) WHERE n.id IN \$ids DETACH DELETE n")
                    .bind(mapOf("ids" to testNodeIds))
            )
        }
    }

    @Test
    fun `provision creates indexes without errors`() {
        drivineStore.provision()
        // Second call should be idempotent
        drivineStore.provision()
    }

    @Test
    fun `save document`() {
        val id = UUID.randomUUID().toString()
        val uri = "test://memgraph-e2e-$id"
        val doc = MaterializedDocument(
            id = id,
            uri = uri,
            title = "Test Document",
            children = emptyList(),
        )
        testNodeIds.add(id)

        val saved = drivineStore.save(doc)
        assertEquals(id, saved.id)

        val found = drivineStore.findContentRootByUri(uri)
        assertNotNull(found)
        assertEquals(id, found!!.id)
    }

    @Test
    fun `save chunk with metadata`() {
        val chunk = Chunk.create(
            text = "This is test chunk content for Memgraph",
            parentId = UUID.randomUUID().toString(),
            metadata = mapOf("source" to "test", "sequence_number" to 0),
        )
        testNodeIds.add(chunk.id)

        drivineStore.save(chunk)

        val found = drivineStore.findById(chunk.id)
        assertNotNull(found)
        assertTrue(found is Chunk)
        assertEquals("This is test chunk content for Memgraph", (found as Chunk).text)
    }

    @Test
    fun `full writeAndChunkDocument ingestion path`() {
        val section = DefaultMaterializedContainerSection(
            id = UUID.randomUUID().toString(),
            title = "Getting Started",
            children = emptyList(),
            metadata = mapOf(
                "text" to "Getting started with Memgraph testing. ".repeat(30),
            ),
        )
        val doc = MaterializedDocument(
            id = UUID.randomUUID().toString(),
            uri = "test://memgraph-full-ingestion-${UUID.randomUUID()}",
            title = "Memgraph Integration Guide",
            children = listOf(section),
        )
        testNodeIds.add(doc.id)
        testNodeIds.add(section.id)

        val chunkIds = drivineStore.writeAndChunkDocument(doc)
        testNodeIds.addAll(chunkIds)

        assertTrue(chunkIds.isNotEmpty(), "Should have created at least one chunk")
        logger.info("Ingested document with {} chunks", chunkIds.size)

        val foundDoc = drivineStore.findContentRootByUri(doc.uri)
        assertNotNull(foundDoc, "Document should be retrievable by URI")

        val foundChunks = drivineStore.findAllChunksById(chunkIds).toList()
        assertEquals(chunkIds.size, foundChunks.size, "All chunks should be retrievable")
    }

    @Test
    fun `save is idempotent`() {
        val chunk = Chunk.create(
            text = "Original text",
            parentId = "parent",
        )
        testNodeIds.add(chunk.id)

        drivineStore.save(chunk)
        drivineStore.save(chunk)

        val found = drivineStore.findById(chunk.id)
        assertNotNull(found)
    }

    @Nested
    inner class ExpandResult {

        @Test
        fun `sequence expansion returns adjacent chunks`() {
            val sectionId = "section-${UUID.randomUUID()}"
            val chunks = (0..2).map { seq ->
                Chunk.create(
                    text = "Chunk content $seq",
                    parentId = UUID.randomUUID().toString(),
                    metadata = mapOf(
                        "container_section_id" to sectionId,
                        "sequence_number" to seq,
                    ),
                ).also { testNodeIds.add(it.id) }
            }
            chunks.forEach { drivineStore.save(it) }

            val result = drivineStore.expandResult(
                chunks[1].id,
                ResultExpander.Method.SEQUENCE,
                elementsToAdd = 1,
            )
            assertEquals(3, result.size)
        }

        @Test
        fun `zoom out returns parent`() {
            val parentId = UUID.randomUUID().toString()
            testNodeIds.add(parentId)
            val section = LeafSection(
                id = parentId,
                title = "Parent Section",
                text = "Parent content",
                parentId = "root",
            )
            drivineStore.save(section)

            val chunk = Chunk.create(
                text = "Child chunk",
                parentId = parentId,
            ).also { testNodeIds.add(it.id) }
            drivineStore.save(chunk)

            val result = drivineStore.expandResult(
                chunk.id,
                ResultExpander.Method.ZOOM_OUT,
                elementsToAdd = 1,
            )
            assertEquals(1, result.size)
            assertEquals(parentId, result.first().id)
        }
    }
}
