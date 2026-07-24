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
package com.embabel.agent.rag.graph.model

import com.embabel.agent.rag.graph.GraphRagServiceProperties
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
 * Spike / capability tests for the graph-traversal views: the recursive `HAS_PARENT` tree, its
 * polymorphic typed form, and the one-hop `zoomOut`.
 *
 * Each test uses a **fresh id prefix** and cleans up by prefix — a reused [GraphObjectManager] snapshots
 * saved/loaded objects, so recreating a deleted id in a later test would hit a stale session snapshot
 * ("skip the write" / missing fields). Unique ids per test avoid the delete-then-recreate entirely.
 */
@SpringBootTest(classes = [Neo4jContentTreeViewTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jContentTreeViewTest {

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
    }

    @Autowired
    lateinit var gom: GraphObjectManager

    @Autowired
    @Qualifier("graph")
    lateinit var pm: PersistenceManager

    private lateinit var prefix: String
    private fun id(suffix: String) = "$prefix-$suffix"

    @BeforeEach
    fun setUp() {
        prefix = "tv-${UUID.randomUUID()}"
    }

    @AfterEach
    fun cleanUp() {
        pm.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.id STARTS WITH \$p DETACH DELETE n").bind(mapOf("p" to prefix))
        )
    }

    @Test
    fun `recursive view loads the whole HAS_PARENT subtree`() {
        // doc <- secA <- {chunkA1, chunkA2};  doc <- secB <- chunkB1
        pm.execute(
            QuerySpecification.withStatement(
                """
                CREATE (doc:ContentElement:Document {id: ${'$'}doc, title: 'Doc', uri: 'test://tv'})
                CREATE (secA:ContentElement:LeafSection {id: ${'$'}secA, title: 'A'})
                CREATE (secB:ContentElement:LeafSection {id: ${'$'}secB, title: 'B'})
                CREATE (ca1:ContentElement:Chunk {id: ${'$'}ca1, text: 'a1'})
                CREATE (ca2:ContentElement:Chunk {id: ${'$'}ca2, text: 'a2'})
                CREATE (cb1:ContentElement:Chunk {id: ${'$'}cb1, text: 'b1'})
                CREATE (secA)-[:HAS_PARENT]->(doc)
                CREATE (secB)-[:HAS_PARENT]->(doc)
                CREATE (ca1)-[:HAS_PARENT]->(secA)
                CREATE (ca2)-[:HAS_PARENT]->(secA)
                CREATE (cb1)-[:HAS_PARENT]->(secB)
                """.trimIndent()
            ).bind(
                mapOf(
                    "doc" to id("doc"), "secA" to id("sec-a"), "secB" to id("sec-b"),
                    "ca1" to id("chunk-a1"), "ca2" to id("chunk-a2"), "cb1" to id("chunk-b1"),
                )
            )
        )

        val root = gom.loadAll(ContentTreeView::class.java, "element.id = '${id("doc")}'").single()
        assertEquals(id("doc"), root.element.id)

        val sections = root.children.sortedBy { it.element.id }
        assertEquals(listOf(id("sec-a"), id("sec-b")), sections.map { it.element.id })
        assertEquals(
            listOf(id("chunk-a1"), id("chunk-a2")),
            sections[0].children.map { it.element.id }.sorted(),
        )
        assertEquals(listOf(id("chunk-b1")), sections[1].children.map { it.element.id })
    }

    @Test
    fun `polymorphic recursive view loads each node as its concrete type`() {
        gom.save(DocumentNode(id = id("doc"), uri = "test://tv", title = "Doc"))
        gom.save(LeafSectionNode(id = id("sec-a"), title = "A", text = "sec a", parentId = id("doc")))
        gom.save(ChunkNode(id = id("chunk-a1"), text = "a1", urtext = "a1", parentId = id("sec-a")))
        gom.save(ChunkNode(id = id("chunk-a2"), text = "a2", urtext = "a2", parentId = id("sec-a")))
        wireHasParent()

        val root = gom.loadAll(TypedContentTreeView::class.java, "element.id = '${id("doc")}'").single()
        assertEquals("MaterializedDocument", root.element.toCoreType()::class.simpleName, "root is a Document")
        val section = root.children.single()
        assertEquals("LeafSection", section.element.toCoreType()::class.simpleName)
        assertEquals(
            setOf("ChunkImpl"),
            section.children.map { it.element.toCoreType()::class.simpleName }.toSet(),
            "leaf children are Chunks",
        )
    }

    @Test
    fun `zoomOut view follows HAS_PARENT one hop to the typed parent`() {
        gom.save(LeafSectionNode(id = id("sec-a"), title = "A", text = "sec a", parentId = id("doc")))
        gom.save(ChunkNode(id = id("chunk-a1"), text = "a1", urtext = "a1", parentId = id("sec-a")))
        wireHasParent()

        val view = gom.loadAll(ZoomOutView::class.java, "element.id = '${id("chunk-a1")}'").single()
        val parent = view.parent?.toCoreType()
        assertEquals("LeafSection", parent?.let { it::class.simpleName }, "zoomOut parent is the typed section")
        assertEquals(id("sec-a"), parent?.id)
    }

    @Test
    fun `expand view walks NEXT_CHUNK multiple hops both directions`() {
        // chain: c1 -> c2 -> c3 -> c4 -> c5
        (1..5).forEach { gom.save(ChunkNode(id = id("c$it"), text = "c$it", urtext = "c$it", parentId = id("sec"))) }
        pm.execute(
            QuerySpecification.withStatement(
                """
                UNWIND [[${'$'}c1,${'$'}c2],[${'$'}c2,${'$'}c3],[${'$'}c3,${'$'}c4],[${'$'}c4,${'$'}c5]] AS pair
                MATCH (a:Chunk {id: pair[0]}), (b:Chunk {id: pair[1]})
                MERGE (a)-[:NEXT_CHUNK]->(b)
                """.trimIndent()
            ).bind((1..5).associate { "c$it" to id("c$it") })
        )

        // anchor at c3: following reaches c4,c5; preceding reaches c2,c1 (multi-hop, flat).
        val view = gom.loadAll(ChunkExpandView::class.java, "chunk.id = '${id("c3")}'").single()
        assertEquals(setOf(id("c4"), id("c5")), view.following.map { it.id }.toSet(), "forward multi-hop")
        assertEquals(setOf(id("c2"), id("c1")), view.preceding.map { it.id }.toSet(), "backward multi-hop")
    }

    /** Materialize HAS_PARENT edges from parentId for this test's nodes. */
    private fun wireHasParent() {
        pm.execute(
            QuerySpecification.withStatement(
                """
                MATCH (child:ContentElement) WHERE child.parentId IS NOT NULL AND child.id STARTS WITH ${'$'}p
                MATCH (parent:ContentElement {id: child.parentId})
                MERGE (child)-[:HAS_PARENT]->(parent)
                """.trimIndent()
            ).bind(mapOf("p" to prefix))
        )
    }
}
