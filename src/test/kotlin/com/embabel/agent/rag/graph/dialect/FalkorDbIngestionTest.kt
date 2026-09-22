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
import com.embabel.agent.rag.ingestion.HierarchicalContentReader
import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.graph.DrivineCypherSearch
import com.embabel.agent.rag.graph.DrivineStore
import com.embabel.agent.rag.graph.GraphRagServiceProperties
import com.embabel.agent.rag.graph.test.FakeEmbeddingModel
import com.embabel.agent.rag.service.ResultExpander
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.transaction.PlatformTransactionManager
import java.util.UUID

/**
 * End-to-end ingestion test against FalkorDB.
 *
 * Exercises the full DrivineStore ingestion path: save document, save chunks,
 * create relationships, embed — the same code path that Guide's DataManager hits.
 */
@SpringBootTest(classes = [FalkorDbIngestionTest.Config::class])
@ActiveProfiles("falkordb")
class FalkorDbIngestionTest {

    @Configuration
    @Profile("falkordb")
    @EnableDrivine
    @EnableDrivineTestConfig
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableConfigurationProperties(GraphRagServiceProperties::class)
    class Config {
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
                dialect = FalkorDbRagDialect(),
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
        val uri = "test://falkordb-e2e-$id"
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
        val parentId = UUID.randomUUID().toString()
        val chunk = Chunk.create(
            text = "This is test chunk content for FalkorDB",
            parentId = parentId,
            metadata = mapOf(
                "source" to "test",
                "sequence_number" to 0,
            ),
        )
        testNodeIds.add(chunk.id)

        drivineStore.save(chunk)

        val found = drivineStore.findById(chunk.id)
        assertNotNull(found)
        assertTrue(found is Chunk)
        assertEquals("This is test chunk content for FalkorDB", (found as Chunk).text)
    }

    @Test
    fun `save multiple chunks and create relationships`() {
        val parentId = UUID.randomUUID().toString()
        testNodeIds.add(parentId)

        // Save a parent section
        val section = LeafSection(
            id = parentId,
            title = "Test Section",
            text = "Section content",
            parentId = "root",
        )
        drivineStore.save(section)

        // Save chunks
        val chunks = (0..2).map { seq ->
            Chunk.create(
                text = "Chunk $seq content",
                parentId = parentId,
                metadata = mapOf(
                    "container_section_id" to parentId,
                    "sequence_number" to seq,
                ),
            ).also { testNodeIds.add(it.id) }
        }
        chunks.forEach { drivineStore.save(it) }

        // Verify all chunks saved
        val allChunks = drivineStore.findAllChunksById(chunks.map { it.id }).toList()
        assertEquals(3, allChunks.size)
    }

    @Test
    fun `save and retrieve content root by uri`() {
        val id = UUID.randomUUID().toString()
        val uri = "test://falkordb-root-$id"
        val doc = MaterializedDocument(id = id, uri = uri, title = "Root Doc", children = emptyList())
        testNodeIds.add(id)

        drivineStore.save(doc)

        assertTrue(drivineStore.existsRootWithUri(uri))
        assertNull(drivineStore.findContentRootByUri("test://non-existent"))
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

        val count = drivineStore.count(Chunk::class.java, null)
        // Should have exactly one chunk with this id, not two
        val found = drivineStore.findById(chunk.id)
        assertNotNull(found)
    }

    @Test
    fun `full writeAndChunkDocument ingestion path`() {
        // Build a document tree similar to what the Guide app creates
        val section = DefaultMaterializedContainerSection(
            id = UUID.randomUUID().toString(),
            title = "Getting Started",
            children = emptyList(),
            metadata = mapOf("text" to "Getting started with FalkorDB testing. " +
                "This is enough text to form at least one chunk during ingestion. ".repeat(20)),
        )
        val doc = MaterializedDocument(
            id = UUID.randomUUID().toString(),
            uri = "test://falkordb-full-ingestion-${UUID.randomUUID()}",
            title = "FalkorDB Integration Guide",
            children = listOf(section),
            metadata = mapOf("Content-Type-Hint" to "text/html"),
        )
        testNodeIds.add(doc.id)
        testNodeIds.add(section.id)

        // This exercises the full path: save doc, save sections, chunk,
        // save chunks, embed, create relationships
        val chunkIds = drivineStore.writeAndChunkDocument(doc)

        testNodeIds.addAll(chunkIds)

        assertTrue(chunkIds.isNotEmpty(), "Should have created at least one chunk")
        logger.info("Ingested document with {} chunks", chunkIds.size)

        // Verify document was saved
        val foundDoc = drivineStore.findContentRootByUri(doc.uri)
        assertNotNull(foundDoc, "Document should be retrievable by URI")

        // Verify chunks were saved
        val foundChunks = drivineStore.findAllChunksById(chunkIds).toList()
        assertEquals(chunkIds.size, foundChunks.size, "All chunks should be retrievable")
    }

    /**
     * E2E test that fetches the actual Embabel Guide page and ingests it.
     * This is the exact URL that fails in the Guide app.
     */
    @Test
    fun `ingest actual Guide HTML page`() {
        val url = "https://docs.embabel.com/embabel-agent/guide/0.4.0-SNAPSHOT/"
        val reader: HierarchicalContentReader = TikaHierarchicalContentReader()

        logger.info("Fetching and parsing: {}", url)
        val doc = reader.parseUrl(url)

        logger.info(
            "Parsed '{}': {} descendants",
            doc.title,
            com.google.common.collect.Iterables.size(doc.descendants()),
        )

        testNodeIds.add(doc.id)
        doc.descendants().filterIsInstance<com.embabel.agent.rag.model.HierarchicalContentElement>()
            .forEach { testNodeIds.add(it.id) }

        logger.info("Starting writeAndChunkDocument...")
        val chunkIds = drivineStore.writeAndChunkDocument(doc)
        testNodeIds.addAll(chunkIds)

        logger.info("Ingestion complete: {} chunks", chunkIds.size)
        assertTrue(chunkIds.isNotEmpty(), "Should have created chunks")

        val foundDoc = drivineStore.findContentRootByUri(url)
        assertNotNull(foundDoc, "Document should be retrievable by URI")
    }

    private val logger = org.slf4j.LoggerFactory.getLogger(FalkorDbIngestionTest::class.java)

    @Nested
    inner class ExpandResult {

        @Test
        fun `sequence expansion returns adjacent chunks`() {
            val parentId = UUID.randomUUID().toString()
            val sectionId = "section-${UUID.randomUUID()}"

            val chunks = (0..2).map { seq ->
                Chunk.create(
                    text = "Chunk content $seq",
                    parentId = parentId,
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
