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
import com.embabel.agent.rag.model.DefaultMaterializedContainerSection
import com.embabel.agent.rag.model.LeafSection
import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.model.NavigableSection
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
import java.time.Instant
import java.util.UUID

/**
 * A stored document reads back in the order it was written: [GraphObjectManagerStore.outline] lists
 * its sections by the ordinal `createInternalRelationships` stamps, a container before what it holds.
 */
@SpringBootTest(classes = [Neo4jDocumentOutlineTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jDocumentOutlineTest {

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

    private lateinit var prefix: String
    private fun id(s: String) = "$prefix-$s"

    @BeforeEach
    fun setUp() {
        prefix = "ol-${UUID.randomUUID()}"
        store.provision()
    }

    @AfterEach
    fun cleanUp() {
        pm.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.id STARTS WITH \$p OR n.root_document_id STARTS WITH \$p DETACH DELETE n").bind(mapOf("p" to prefix))
        )
    }

    private fun leaf(name: String, parent: String) =
        LeafSection(id = id(name), uri = null, title = name, text = "$name text", parentId = id(parent), metadata = emptyMap())

    private fun document(children: List<NavigableSection>) = MaterializedDocument(
        id = id("doc"), uri = "test://$prefix", title = "Doc", ingestionTimestamp = Instant.now(),
        children = children, metadata = emptyMap(),
    )

    @Test
    fun `outline lists sections in reading order, a container before its own sections`() {
        val a1 = leaf("a1", "a")
        val a2 = leaf("a2", "a")
        val a = DefaultMaterializedContainerSection(
            id = id("a"), uri = null, title = "a", children = listOf(a1, a2), parentId = id("doc"), metadata = emptyMap(),
        )
        val intro = leaf("intro", "doc")
        val b = leaf("b", "doc")
        val root = document(listOf(intro, a, b))
        store.writeAndChunkDocument(root)

        val outline = store.outline(root.uri)

        assertEquals(listOf("intro", "a", "a1", "a2", "b").map { id(it) }, outline.map { it.id })
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), outline.map { it.ordinal })
        assertEquals(listOf("doc", "doc", "a", "a", "doc").map { id(it) }, outline.map { it.parentId })
        assertEquals(listOf(1, 1, 2, 2, 1), outline.map { it.depth })
        assertEquals(listOf(true, false, true, true, true), outline.map { it.leaf })
    }

    @Test
    fun `saving a section again keeps its place`() {
        val one = leaf("one", "doc")
        val two = leaf("two", "doc")
        val root = document(listOf(one, two))
        store.writeAndChunkDocument(root)

        store.save(two)

        assertEquals(listOf(0L, 1L), store.outline(root.uri).map { it.ordinal })
    }

    @Test
    fun `outline takes a window of the document`() {
        val names = listOf("one", "two", "three", "four")
        val root = document(names.map { leaf(it, "doc") })
        store.writeAndChunkDocument(root)

        val page = store.outline(root.uri, skip = 1, limit = 2)

        assertEquals(listOf("two", "three").map { id(it) }, page.map { it.id })
    }

    private fun stored(): Int = pm.query(
        QuerySpecification
            .withStatement("MATCH (n) WHERE n.id STARTS WITH \$p OR n.root_document_id STARTS WITH \$p RETURN count(n)")
            .bind(mapOf("p" to prefix))
            .transform(Int::class.java)
    ).single()

    @Test
    fun `deleting a document removes its sections and chunks, and nothing else`() {
        val a = DefaultMaterializedContainerSection(
            id = id("a"), uri = null, title = "a", children = listOf(leaf("a1", "a"), leaf("a2", "a")),
            parentId = id("doc"), metadata = emptyMap(),
        )
        val root = document(listOf(leaf("intro", "doc"), a))
        store.writeAndChunkDocument(root)
        val before = stored()
        // Another document's section, which the delete must leave alone.
        store.save(LeafSection(id = id("other"), uri = null, title = "other", text = "x", parentId = id("elsewhere"), metadata = emptyMap()))

        val result = store.deleteRootAndDescendants(root.uri)

        assertEquals(before, result?.deletedCount)
        assertEquals(1, stored(), "only the other document's section is left")
    }

    @Test
    fun `deleting a document that is not stored reports nothing`() {
        assertEquals(null, store.deleteRootAndDescendants("test://$prefix-absent"))
    }

    @Test
    fun `a document that is not stored has an empty outline`() {
        assertEquals(emptyList<DocumentSection>(), store.outline("test://$prefix-absent"))
    }
}
