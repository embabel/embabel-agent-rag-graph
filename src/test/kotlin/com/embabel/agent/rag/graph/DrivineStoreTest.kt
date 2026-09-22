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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.graph.test.TestAppContext
import com.embabel.agent.rag.service.ResultExpander
import com.embabel.common.ai.model.ModelProvider
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

@SpringBootTest(classes = [TestAppContext::class])
@ImportAutoConfiguration(exclude = [McpClientAutoConfiguration::class])
class DrivineStoreTest {
    @MockitoBean
    lateinit var modelProvider: ModelProvider

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
            val deleteStatement = "MATCH (n) WHERE n.id IN \$ids DETACH DELETE n"
            persistenceManager.execute(
                QuerySpecification.withStatement(deleteStatement)
                    .bind(mapOf("ids" to testNodeIds))
            )
        }
    }

    private fun createTestNode(labels: List<String>, uri: String): String {
        val id = UUID.randomUUID().toString()
        val labelsString = labels.joinToString(":")
        val createStatement = """
            CREATE (n:$labelsString {
                id: ${'$'}id,
                uri: ${'$'}uri,
                text: 'Test content',
                parentId: null,
                ingestionTimestamp: datetime()
            })
            RETURN n.id as id
        """.trimIndent()
        persistenceManager.execute(
            QuerySpecification.withStatement(createStatement)
                .bind(mapOf("id" to id, "uri" to uri))
        )
        testNodeIds.add(id)
        return id
    }

    @Test
    fun `should return info`() {
        val info = drivineStore.info()
        println("Info: $info")
        assertNotNull(info)
    }

    @Test
    fun `should find content root by uri for Document node`() {
        val uri = "test://document-node-${UUID.randomUUID()}"
        createTestNode(listOf("ContentElement", "Document"), uri)

        val result = drivineStore.findContentRootByUri(uri)

        println("Found Document content root: $result")
        assertNotNull(result, "Expected to find a ContentRoot with Document label and URI: $uri")
    }

    @Test
    fun `should find content root by uri for Document with Section labels`() {
        val uri = "test://document-section-${UUID.randomUUID()}"
        createTestNode(listOf("ContentElement", "Document", "Section", "ContainerSection"), uri)

        val result = drivineStore.findContentRootByUri(uri)

        println("Found Document+Section content root: $result")
        assertNotNull(result, "Expected to find a ContentRoot with Document+Section labels and URI: $uri")
    }

    @Test
    fun `should find content root by uri for ContentRoot label without Document`() {
        val uri = "test://content-root-only-${UUID.randomUUID()}"
        createTestNode(listOf("ContentElement", "ContentRoot"), uri)

        val result = drivineStore.findContentRootByUri(uri)

        println("Found ContentRoot (no Document label): $result")
        assertNotNull(result, "Expected to find a ContentRoot with ContentRoot label (no Document) and URI: $uri")
    }

    @Test
    fun `should return null for non-existent uri`() {
        val uri = "test://non-existent-${UUID.randomUUID()}"

        val result = drivineStore.findContentRootByUri(uri)

        assertNull(result, "Expected null for non-existent URI")
    }

    @Test
    fun `should not find Chunk node as content root`() {
        val uri = "test://chunk-node-${UUID.randomUUID()}"
        createTestNode(listOf("ContentElement", "Chunk"), uri)

        val result = drivineStore.findContentRootByUri(uri)

        assertNull(result, "Chunk nodes should not be returned as ContentRoot")
    }

    @Nested
    inner class ExpandResult {

        @Test
        fun `sequence expansion should return adjacent chunks`() {
            val parentId = UUID.randomUUID().toString()
            val sectionId = "section-${UUID.randomUUID()}"

            val chunks = (0..2).map { seq ->
                val chunkId = UUID.randomUUID().toString()
                testNodeIds.add(chunkId)
                Chunk(
                    id = chunkId,
                    text = "Chunk content $seq",
                    parentId = parentId,
                    metadata = mapOf(
                        "container_section_id" to sectionId,
                        "sequence_number" to seq
                    )
                )
            }
            chunks.forEach { drivineStore.save(it) }

            val result = drivineStore.expandResult(
                chunks[1].id,
                ResultExpander.Method.SEQUENCE,
                elementsToAdd = 1
            )

            assertEquals(3, result.size)
            assertEquals(chunks.map { it.id }, result.map { it.id })
        }

        @Test
        fun `sequence expansion should return only original chunk when metadata missing`() {
            val chunkId = UUID.randomUUID().toString()
            testNodeIds.add(chunkId)
            val chunk = Chunk(
                id = chunkId,
                text = "Chunk without sequence metadata",
                parentId = "parent",
                metadata = emptyMap()
            )
            drivineStore.save(chunk)

            val result = drivineStore.expandResult(
                chunkId,
                ResultExpander.Method.SEQUENCE,
                elementsToAdd = 1
            )

            assertEquals(1, result.size)
            assertEquals(chunkId, result.first().id)
        }

        @Test
        fun `sequence expansion should return empty list for non-existent chunk`() {
            val result = drivineStore.expandResult(
                "non-existent-${UUID.randomUUID()}",
                ResultExpander.Method.SEQUENCE,
                elementsToAdd = 1
            )

            assertTrue(result.isEmpty())
        }

        @Test
        fun `sequence expansion should not cross container sections`() {
            val parentId = UUID.randomUUID().toString()
            val section1 = "section1-${UUID.randomUUID()}"
            val section2 = "section2-${UUID.randomUUID()}"

            val s1Chunks = (0..1).map { seq ->
                val chunkId = UUID.randomUUID().toString()
                testNodeIds.add(chunkId)
                Chunk(
                    id = chunkId,
                    text = "S1 chunk $seq",
                    parentId = parentId,
                    metadata = mapOf(
                        "container_section_id" to section1,
                        "sequence_number" to seq
                    )
                )
            }
            val s2Chunk = Chunk(
                id = UUID.randomUUID().toString().also { testNodeIds.add(it) },
                text = "S2 chunk 0",
                parentId = parentId,
                metadata = mapOf(
                    "container_section_id" to section2,
                    "sequence_number" to 0
                )
            )
            (s1Chunks + s2Chunk).forEach { drivineStore.save(it) }

            val result = drivineStore.expandResult(
                s1Chunks[0].id,
                ResultExpander.Method.SEQUENCE,
                elementsToAdd = 5
            )

            assertEquals(2, result.size)
            assertTrue(result.all { it.id in s1Chunks.map { c -> c.id } })
        }

        @Test
        fun `zoomOut should return parent element`() {
            val parentId = UUID.randomUUID().toString()
            testNodeIds.add(parentId)
            val leafSection = LeafSection(
                id = parentId,
                title = "Parent Section",
                text = "Parent section content",
                parentId = "root-id",
            )
            drivineStore.save(leafSection)

            val chunkId = UUID.randomUUID().toString()
            testNodeIds.add(chunkId)
            val chunk = Chunk(
                id = chunkId,
                text = "Test chunk content for zoom out",
                parentId = parentId,
                metadata = mapOf(
                    "container_section_id" to parentId,
                    "sequence_number" to 0
                )
            )
            drivineStore.save(chunk)

            val result = drivineStore.expandResult(
                chunkId,
                ResultExpander.Method.ZOOM_OUT,
                elementsToAdd = 1
            )

            assertEquals(1, result.size)
            val parent = result.first()
            assertEquals(parentId, parent.id)
            assertTrue(parent is LeafSection)
            assertEquals("Parent Section", (parent as LeafSection).title)
        }

        @Test
        fun `zoomOut should return empty list when parent not found`() {
            val chunkId = UUID.randomUUID().toString()
            testNodeIds.add(chunkId)
            val chunk = Chunk(
                id = chunkId,
                text = "Orphan chunk",
                parentId = "non-existent-parent-${UUID.randomUUID()}",
                metadata = emptyMap()
            )
            drivineStore.save(chunk)

            val result = drivineStore.expandResult(
                chunkId,
                ResultExpander.Method.ZOOM_OUT,
                elementsToAdd = 1
            )

            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class ChunkMetadataPersistence {

        @Test
        fun `should persist chunk metadata`() {
            val chunkId = UUID.randomUUID().toString()
            val parentId = UUID.randomUUID().toString()
            val metadata = mapOf(
                "source" to "test-source",
                "section_title" to "Test Section",
                "page_number" to 42
            )
            val chunk = Chunk(
                id = chunkId,
                text = "This is test chunk content",
                parentId = parentId,
                metadata = metadata
            )
            testNodeIds.add(chunkId)

            drivineStore.save(chunk)

            val retrieved = drivineStore.findById(chunkId)
            println("Saved chunk: $chunk")
            println("Retrieved chunk: $retrieved")

            assertNotNull(retrieved, "Chunk should be retrievable after save")
            require(retrieved is Chunk) { "Retrieved element should be a Chunk" }
            assertEquals(chunkId, retrieved.id)
            assertEquals("This is test chunk content", retrieved.text)
            assertEquals(parentId, retrieved.parentId)

            // Verify metadata is preserved
            assertEquals("test-source", retrieved.metadata["source"], "source metadata should be preserved")
            assertEquals("Test Section", retrieved.metadata["section_title"], "section_title metadata should be preserved")
            // Neo4j returns integers as Long
            assertEquals(42L, retrieved.metadata["page_number"], "page_number metadata should be preserved")
        }

        @Test
        fun `should persist chunk with empty metadata`() {
            val chunkId = UUID.randomUUID().toString()
            val parentId = UUID.randomUUID().toString()
            val chunk = Chunk(
                id = chunkId,
                text = "Chunk with no metadata",
                parentId = parentId,
                metadata = emptyMap()
            )
            testNodeIds.add(chunkId)

            drivineStore.save(chunk)

            val retrieved = drivineStore.findById(chunkId)
            assertNotNull(retrieved, "Chunk should be retrievable after save")
            require(retrieved is Chunk) { "Retrieved element should be a Chunk" }
            assertEquals(chunkId, retrieved.id)
        }

        @Test
        fun `should retrieve chunks by id with metadata preserved`() {
            val chunkId = UUID.randomUUID().toString()
            val parentId = UUID.randomUUID().toString()
            val metadata = mapOf(
                "source" to "bulk-test-source",
                "custom_field" to "custom_value"
            )
            val chunk = Chunk(
                id = chunkId,
                text = "Bulk retrieval test chunk",
                parentId = parentId,
                metadata = metadata
            )
            testNodeIds.add(chunkId)

            drivineStore.save(chunk)

            val retrieved = drivineStore.findAllChunksById(listOf(chunkId)).toList()
            println("Retrieved chunks: $retrieved")

            assertEquals(1, retrieved.size, "Should retrieve exactly one chunk")
            val retrievedChunk = retrieved.first()
            assertEquals(chunkId, retrievedChunk.id)
            assertEquals("bulk-test-source", retrievedChunk.metadata["source"], "source metadata should be preserved in bulk retrieval")
            assertEquals("custom_value", retrievedChunk.metadata["custom_field"], "custom_field metadata should be preserved in bulk retrieval")
        }
    }
}
