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

import com.embabel.agent.rag.graph.FixedLocationLogicalQueryResolver
import com.embabel.agent.rag.graph.LogicalQueryResolver
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.UUID

/**
 * Integration tests for FalkorDB compatibility.
 *
 * Runs against a FalkorDB testcontainer (default) or local instance
 * (`USE_LOCAL_FALKORDB=true`).
 */
@SpringBootTest(classes = [FalkorDbIntegrationTest.Config::class])
@ActiveProfiles("falkordb")
class FalkorDbIntegrationTest {

    @Configuration
    @Profile("falkordb")
    @EnableDrivine
    @EnableDrivineTestConfig
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableConfigurationProperties
    class Config {
        @Bean("graph")
        fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager {
            return factory.get("graph")
        }
    }

    @Autowired
    @Qualifier("graph")
    lateinit var persistenceManager: PersistenceManager

    private val queryResolver: LogicalQueryResolver = FixedLocationLogicalQueryResolver()
    private val testNodeIds = mutableListOf<String>()

    @AfterEach
    fun tearDown() {
        if (testNodeIds.isNotEmpty()) {
            persistenceManager.execute(
                QuerySpecification.withStatement("MATCH (n) WHERE n.id IN \$ids DETACH DELETE n")
                    .bind(mapOf("ids" to testNodeIds))
            )
        }
    }

    @Nested
    inner class BasicParameterBinding {

        @Test
        fun `multiple SET clauses on separate lines`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            // This is the pattern in save_content_element.cypher
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(
                        """MERGE (e:TestNode {id: ${'$'}id})
                           SET e:ExtraLabel
                           SET e.name = ${'$'}name
                           SET e.lastModified = timestamp()""".trimIndent()
                    )
                    .bind(mapOf("id" to id, "name" to "multiset"))
            )
        }

        @Test
        fun `SET with large text containing special characters`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            val largeText = "Code: val x = foo(); bar(x); " +
                "HTML: <div class=\"test\">content</div>; " +
                "More code: if (a > b) { return c; } ".repeat(50)
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(
                        """MERGE (e:TestNode {id: ${'$'}id})
                           SET e:ContentElement:Chunk
                           SET e.text = ${'$'}text
                           SET e.lastModified = timestamp()""".trimIndent()
                    )
                    .bind(mapOf("id" to id, "text" to largeText))
            )
        }

        @Test
        fun `SET with Instant parameter works via TemporalCoercer`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            // Drivine 0.0.34+ auto-coerces Instant to string for FalkorDB
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(
                        "MERGE (e:TestNode {id: \$id}) SET e.ts = \$ts"
                    )
                    .bind(mapOf("id" to id, "ts" to java.time.Instant.now()))
            )
        }



        @Test
        fun `text containing dollar signs`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            val textWithDollars = "Kotlin code: val x = \"\${input.name}!\""
            persistenceManager.execute(
                QuerySpecification
                    .withStatement("MERGE (e:TestNode {id: \$id}) SET e.text = \$text")
                    .bind(mapOf("id" to id, "text" to textWithDollars))
            )
        }

        @Test
        fun `SET with null property value`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(
                        """MERGE (e:ContentElement {id: ${'$'}id})
                           SET e:Document:ContentRoot
                           SET e.parentId = ${'$'}parentId
                           SET e.lastModifiedDate = timestamp()""".trimIndent()
                    )
                    .bind(mapOf("id" to id, "parentId" to null))
            )
        }

        @Test
        fun `save with many flattened properties including null`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            val cypher = """MERGE (e:ContentElement {id: ${'$'}id})
                SET e:Document:ContentRoot:Section:ContainerSection
                SET e.id = ${'$'}prop_0, e.uri = ${'$'}prop_1, e.`Content-Type-Hint` = ${'$'}prop_2, e.parentId = ${'$'}prop_3, e.title = ${'$'}prop_4, e.ingestionTimestamp = ${'$'}prop_5
                SET e.lastModifiedDate = timestamp()
                RETURN properties(e)""".trimIndent()
            persistenceManager.query(
                QuerySpecification
                    .withStatement(cypher)
                    .bind(mapOf(
                        "id" to id,
                        "prop_0" to id,
                        "prop_1" to "https://docs.embabel.com/embabel-agent/guide/0.4.0-SNAPSHOT/",
                        "prop_2" to "text/html",
                        "prop_3" to null,
                        "prop_4" to "Embabel Agent Framework User Guide",
                        "prop_5" to "2026-04-23T03:25:57.680780Z",
                    ))
                    .transform(Map::class.java)
            )
        }

        @Test
        fun `exact failing text from Guide ingestion`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            val text = javaClass.classLoader.getResource("falkordb-failing-text.txt")!!.readText()
            println("Failing text length: ${text.length}")
            val cypher = """MERGE (e:ContentElement {id: ${'$'}id})
                SET e:Section:LeafSection
                SET e.id = ${'$'}prop_0, e.uri = ${'$'}prop_1, e.`Content-Type-Hint` = ${'$'}prop_2, e.root_document_id = ${'$'}prop_3, e.container_section_id = ${'$'}prop_4, e.leaf_section_id = ${'$'}prop_5, e.parentId = ${'$'}prop_6, e.title = ${'$'}prop_7, e.text = ${'$'}prop_8
                SET e.lastModifiedDate = timestamp()
                RETURN properties(e)""".trimIndent()
            persistenceManager.query(
                QuerySpecification
                    .withStatement(cypher)
                    .bind(mapOf(
                        "id" to id,
                        "prop_0" to id,
                        "prop_1" to "https://docs.embabel.com/embabel-agent/guide/0.4.0-SNAPSHOT/",
                        "prop_2" to "text/html",
                        "prop_3" to java.util.UUID.randomUUID().toString(),
                        "prop_4" to java.util.UUID.randomUUID().toString(),
                        "prop_5" to id,
                        "prop_6" to java.util.UUID.randomUUID().toString(),
                        "prop_7" to "Creating a Simple UnfoldingTool",
                        "prop_8" to text,
                    ))
                    .transform(Map::class.java)
            )
        }

        @Test
        fun `text with triple quoted JSON and special chars`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            // Content matching the failing Guide section - triple quotes, braces, arrows
            val text = ("""Tool.Result.text(${'"'}${'"'}${'"'}{"rows": 5}${'"'}${'"'}${'"'}) """ +
                """input -&gt; Tool.Result.text(${'"'}${'"'}${'"'}{"id": 123}${'"'}${'"'}${'"'}) """ +
                """input -&gt; Tool.Result.text(${'"'}${'"'}${'"'}{"deleted": true}${'"'}${'"'}${'"'}) """).repeat(15)
            val cypher = """MERGE (e:ContentElement {id: ${'$'}id})
                SET e:Section:LeafSection
                SET e.id = ${'$'}prop_0, e.uri = ${'$'}prop_1, e.`Content-Type-Hint` = ${'$'}prop_2, e.root_document_id = ${'$'}prop_3, e.container_section_id = ${'$'}prop_4, e.leaf_section_id = ${'$'}prop_5, e.parentId = ${'$'}prop_6, e.title = ${'$'}prop_7, e.text = ${'$'}prop_8
                SET e.lastModifiedDate = timestamp()
                RETURN properties(e)""".trimIndent()
            persistenceManager.query(
                QuerySpecification
                    .withStatement(cypher)
                    .bind(mapOf(
                        "id" to id,
                        "prop_0" to id,
                        "prop_1" to "https://test.com",
                        "prop_2" to "text/html",
                        "prop_3" to UUID.randomUUID().toString(),
                        "prop_4" to UUID.randomUUID().toString(),
                        "prop_5" to id,
                        "prop_6" to UUID.randomUUID().toString(),
                        "prop_7" to "Creating a Simple UnfoldingTool",
                        "prop_8" to text,
                    ))
                    .transform(Map::class.java)
            )
        }

        @Test
        fun `text with triple quotes and dollar braces`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            // Exact pattern from the failing Guide section
            val text = """val result = addTool.call(${"\"\"\""}{"a": 5, "b": 3}${"\"\"\""}) // Result: {"sum":8} val greetTool = Tool.fromFunction<GreetRequest, String>(name = "greet") { input -> "Hello ${"$"}{input.name}!" }"""
            persistenceManager.execute(
                QuerySpecification
                    .withStatement("MERGE (e:TestNode {id: \$id}) SET e.text = \$text")
                    .bind(mapOf("id" to id, "text" to text))
            )
            // Verify roundtrip
            @Suppress("UNCHECKED_CAST")
            val props = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("MATCH (n:TestNode {id: \$id}) RETURN properties(n) AS props")
                    .bind(mapOf("id" to id))
                    .transform(Map::class.java)
            ) as Map<String, Any>
            assertTrue((props["text"] as String).contains("input.name"))
        }

        @Test
        fun `large text with many properties and 9 params`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            // Same structure as save_content_element for a LeafSection - 9 props
            val largeText = "For tools with complex input, use Tool.fromFunction(). " +
                "val addTool = Tool.fromFunction<AddRequest, AddResult>(name = \"add\") { input -> AddResult(input.a + input.b) } " +
                "val result = addTool.call(\"\"\"{\"a\": 5, \"b\": 3}\"\"\") ".repeat(20)
            val cypher = """MERGE (e:ContentElement {id: ${'$'}id})
                SET e:Section:LeafSection
                SET e.id = ${'$'}prop_0, e.uri = ${'$'}prop_1, e.`Content-Type-Hint` = ${'$'}prop_2, e.root_document_id = ${'$'}prop_3, e.container_section_id = ${'$'}prop_4, e.leaf_section_id = ${'$'}prop_5, e.parentId = ${'$'}prop_6, e.title = ${'$'}prop_7, e.text = ${'$'}prop_8
                SET e.lastModifiedDate = timestamp()
                RETURN properties(e)""".trimIndent()
            persistenceManager.query(
                QuerySpecification
                    .withStatement(cypher)
                    .bind(mapOf(
                        "id" to id,
                        "prop_0" to id,
                        "prop_1" to "https://docs.embabel.com/test",
                        "prop_2" to "text/html",
                        "prop_3" to UUID.randomUUID().toString(),
                        "prop_4" to UUID.randomUUID().toString(),
                        "prop_5" to id,
                        "prop_6" to UUID.randomUUID().toString(),
                        "prop_7" to "Creating Strongly Typed Tools",
                        "prop_8" to largeText,
                    ))
                    .transform(Map::class.java)
            )
        }

        @Test
        fun `multiple SET clauses on single line`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(
                        "MERGE (e:TestNode {id: \$id}) SET e:ExtraLabel SET e.name = \$name SET e.lastModified = timestamp()"
                    )
                    .bind(mapOf("id" to id, "name" to "singleline"))
            )
        }

        @Test
        fun `property names with hyphens work via backtick quoting`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(
                        "CREATE (n:TestNode {id: \$id, `Content-Type-Hint`: \$hint})"
                    )
                    .bind(mapOf("id" to id, "hint" to "text/html"))
            )
            @Suppress("UNCHECKED_CAST")
            val props = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("MATCH (n:TestNode {id: \$id}) RETURN properties(n) AS props")
                    .bind(mapOf("id" to id))
                    .transform(Map::class.java)
            ) as Map<String, Any>
            assertEquals("text/html", props["Content-Type-Hint"])
        }

        @Test
        fun `SET with map parameter fails on FalkorDB`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            // Map-typed parameters are not accepted by FalkorDB; the exact error
            // message varies between versions, so we assert only that it throws.
            org.junit.jupiter.api.assertThrows<Exception> {
                persistenceManager.execute(
                    QuerySpecification
                        .withStatement(
                            """MERGE (e:ContentElement {id: ${'$'}id})
                               SET e += ${'$'}properties""".trimIndent()
                        )
                        .bind(mapOf(
                            "id" to id,
                            "properties" to mapOf("title" to "Test"),
                        ))
                )
            }
        }

        @Test
        fun `SET with individual properties works`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(
                        """MERGE (e:ContentElement {id: ${'$'}id})
                           SET e.title = ${'$'}title, e.uri = ${'$'}uri""".trimIndent()
                    )
                    .bind(mapOf(
                        "id" to id,
                        "title" to "Test",
                        "uri" to "test://individual",
                    ))
            )
            val count = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("MATCH (n:ContentElement {id: \$id}) RETURN count(n) AS count")
                    .bind(mapOf("id" to id))
                    .transform(Int::class.java)
            )
            assertEquals(1, count)
        }

        @Test
        fun `render and bind combined works`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            persistenceManager.execute(
                QuerySpecification
                    .withStatement("CREATE (n:\$(\$label) {id: \$id, name: \$name})")
                    .render(mapOf("label" to "DynamicNode"))
                    .bind(mapOf("id" to id, "name" to "rendered"))
            )
            val count = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("MATCH (n:DynamicNode {id: \$id}) RETURN count(n) AS count")
                    .bind(mapOf("id" to id))
                    .transform(Int::class.java)
            )
            assertEquals(1, count)
        }

        @Test
        fun `simple parameterized query works`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            persistenceManager.execute(
                QuerySpecification
                    .withStatement("CREATE (n:TestNode {id: \$id, name: \$name})")
                    .bind(mapOf("id" to id, "name" to "test"))
            )
            val count = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("MATCH (n:TestNode {id: \$id}) RETURN count(n) AS count")
                    .bind(mapOf("id" to id))
                    .transform(Int::class.java)
            )
            assertEquals(1, count)
        }
    }

    @Nested
    inner class SaveContentElement {

        @Test
        fun `save content element with render labels and flattened properties`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            val cypher = queryResolver.resolve("save_content_element")!!
            val additionalLabels = listOf("Document", "ContentRoot")
            val properties = mapOf("id" to id, "uri" to "test://save-test", "title" to "Test Document")
            val setClause = "SET " + flattenToAssignments("e", properties)

            persistenceManager.query(
                QuerySpecification
                    .withStatement(cypher)
                    .render(mapOf(
                        "additionalLabels" to additionalLabels,
                        "setClause" to setClause,
                    ))
                    .bind(mapOf("id" to id) + flattenToBindParams(properties))
                    .transform(Map::class.java)
            )

            @Suppress("UNCHECKED_CAST")
            val result = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("MATCH (n {id: \$id}) RETURN labels(n) AS labels")
                    .bind(mapOf("id" to id))
                    .transform(List::class.java)
            ) as List<String>

            assertTrue(result.contains("ContentElement"))
            assertTrue(result.contains("Document"))
            assertTrue(result.contains("ContentRoot"))
        }

        @Test
        fun `save is idempotent - second save updates properties`() {
            val id = UUID.randomUUID().toString()
            testNodeIds.add(id)
            val cypher = queryResolver.resolve("save_content_element")!!
            val labels = listOf("Chunk")

            fun saveWith(props: Map<String, Any>) {
                val setClause = "SET " + flattenToAssignments("e", props)
                persistenceManager.query(
                    QuerySpecification
                        .withStatement(cypher)
                        .render(mapOf(
                            "additionalLabels" to labels,
                            "setClause" to setClause,
                        ))
                        .bind(mapOf("id" to id) + flattenToBindParams(props))
                        .transform(Map::class.java)
                )
            }

            saveWith(mapOf("id" to id, "text" to "original"))
            saveWith(mapOf("id" to id, "text" to "updated"))

            val count = persistenceManager.getOne(
                QuerySpecification
                    .withStatement("MATCH (n {id: \$id}) RETURN count(n) AS count")
                    .bind(mapOf("id" to id))
                    .transform(Int::class.java)
            )
            assertEquals(1, count)
        }
    }

    @Nested
    inner class Relationships {

        @Test
        fun `create relationship with render type and flattened properties`() {
            val fromId = UUID.randomUUID().toString()
            val toId = UUID.randomUUID().toString()
            testNodeIds.addAll(listOf(fromId, toId))

            persistenceManager.execute(
                QuerySpecification.withStatement(
                    "CREATE (:Entity {id: \$fromId}), (:Entity {id: \$toId})"
                ).bind(mapOf("fromId" to fromId, "toId" to toId))
            )

            val cypher = queryResolver.resolve("create_named_entity_relationship")!!
            val relProps = mapOf("since" to 2024)
            val setClause = if (relProps.isNotEmpty()) {
                "SET " + flattenToAssignments("r", relProps)
            } else ""
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(cypher)
                    .render(mapOf(
                        "relType" to "KNOWS",
                        "setClause" to setClause,
                    ))
                    .bind(mapOf(
                        "fromId" to fromId,
                        "fromType" to "Entity",
                        "toId" to toId,
                        "toType" to "Entity",
                    ) + flattenToBindParams(relProps))
            )

            val relType = persistenceManager.getOne(
                QuerySpecification
                    .withStatement(
                        "MATCH ({id: \$fromId})-[r]->({id: \$toId}) RETURN type(r) AS type"
                    )
                    .bind(mapOf("fromId" to fromId, "toId" to toId))
                    .transform(String::class.java)
            )
            assertEquals("KNOWS", relType)
        }

        @Test
        fun `merge relationship with render type is idempotent`() {
            val fromId = UUID.randomUUID().toString()
            val toId = UUID.randomUUID().toString()
            testNodeIds.addAll(listOf(fromId, toId))

            persistenceManager.execute(
                QuerySpecification.withStatement(
                    "CREATE (:Entity {id: \$fromId}), (:Entity {id: \$toId})"
                ).bind(mapOf("fromId" to fromId, "toId" to toId))
            )

            val cypher = queryResolver.resolve("merge_named_entity_relationship")!!
            fun mergeSpec() = QuerySpecification
                .withStatement(cypher)
                .render(mapOf(
                    "relType" to "WORKS_WITH",
                    "setClause" to "",
                ))
                .bind(mapOf(
                    "fromId" to fromId,
                    "fromType" to "Entity",
                    "toId" to toId,
                    "toType" to "Entity",
                ))

            persistenceManager.execute(mergeSpec())
            persistenceManager.execute(mergeSpec())

            val count = persistenceManager.getOne(
                QuerySpecification
                    .withStatement(
                        "MATCH ({id: \$fromId})-[r]->({id: \$toId}) RETURN count(r) AS count"
                    )
                    .bind(mapOf("fromId" to fromId, "toId" to toId))
                    .transform(Int::class.java)
            )
            assertEquals(1, count)
        }
    }

    /** Flatten properties to a SET assignment string for render params */
    private fun flattenToAssignments(alias: String, props: Map<String, Any?>): String =
        props.entries.mapIndexed { i, (key, _) -> "$alias.$key = \$prop_$i" }.joinToString(", ")

    /** Flatten properties to bind params */
    private fun flattenToBindParams(props: Map<String, Any?>): Map<String, Any?> =
        props.values.mapIndexed { i, value -> "prop_$i" to value }.toMap()
}
