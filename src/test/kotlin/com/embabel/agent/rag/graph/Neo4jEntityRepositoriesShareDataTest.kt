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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.graph.test.DeterministicEmbeddingModel
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.RelationshipDirection
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * [DrivineNamedEntityDataRepository] and [GraphObjectManagerEntityRepository] over one database: what
 * either writes, the other reads as the same entity. That is what lets a deployment move from the
 * first to the second, or run both, with no migration.
 *
 * One thing the object-manager repository writes that the Cypher one does not: the record of the
 * labels it has given a node, in the property [OWNED_LABELS]. The Cypher repository returns every
 * property of a node, so it returns that one too; it is set aside here, by name, and nothing else is.
 */
@SpringBootTest(classes = [Neo4jGomStoreCharacterizationTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jEntityRepositoriesShareDataTest {

    private companion object {
        const val OWNED_LABELS = "__drivine.labels.labels"
    }

    @Autowired
    @Qualifier("graph")
    lateinit var persistenceManager: PersistenceManager

    @Autowired
    lateinit var factory: GraphObjectManagerFactory

    @Autowired
    lateinit var properties: GraphRagServiceProperties

    private val dictionary = DataDictionary.fromClasses("shared", ContractPerson::class.java, ContractMusician::class.java)
    private val embeddings = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel())

    private val cypher: NamedEntityDataRepository by lazy {
        DrivineNamedEntityDataRepository(
            persistenceManager = persistenceManager,
            properties = properties,
            dataDictionary = dictionary,
            embeddingService = embeddings,
            graphObjectManager = factory.get("graph"),
        )
    }

    private val objectManager: NamedEntityDataRepository by lazy {
        GraphObjectManagerEntityRepository(
            gom = factory.get("graph"),
            properties = properties,
            dataDictionary = dictionary,
            embeddingService = embeddings,
            entitySchema = EntitySchemaProvisioner(persistenceManager, properties, { embeddings }),
        )
    }

    private lateinit var tag: String
    private val savedIds = mutableListOf<String>()

    @BeforeEach
    fun newTag() {
        tag = "s${UUID.randomUUID().toString().replace("-", "").take(10)}"
        savedIds.clear()
    }

    @AfterEach
    fun removeSaved() {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.id IN \$ids DETACH DELETE n")
                .bind(mapOf("ids" to savedIds.toList())),
        )
    }

    private fun label(name: String) = "$name$tag"

    private fun id(name: String) = "$name-$tag".also { savedIds += it }

    private fun entity(id: String, vararg labels: String, name: String = id, properties: Map<String, Any> = emptyMap()): NamedEntityData =
        SimpleNamedEntityData(
            id = id,
            uri = null,
            name = name,
            description = "about $name",
            labels = labels.toSet(),
            properties = properties,
            metadata = emptyMap(),
            linkedDomainType = null,
        )

    /** An entity as a comparable value: what a caller can observe of it. */
    private data class Observed(
        val id: String,
        val name: String,
        val description: String,
        val labels: Set<String>,
        val properties: Map<String, Any>,
    )

    private fun NamedEntityData?.observed(): Observed = requireNotNull(this) { "expected a stored entity, found none" }.let {
        Observed(it.id, it.name, it.description, it.labels(), it.properties - OWNED_LABELS)
    }

    private fun bothRead(id: String): Observed {
        val byCypher = cypher.findById(id).observed()
        assertEquals(byCypher, objectManager.findById(id).observed(), "the two repositories read '$id' differently")
        return byCypher
    }

    @Test
    fun `an entity the Cypher repository saves is read the same by both`() {
        val id = id("verse")

        cypher.save(entity(id, label("Verse"), "ContractMusician", properties = mapOf("book" to "Genesis", "chapter" to 1L, "source-ref" to "kjv")))

        val read = bothRead(id)
        assertEquals(setOf(NamedEntityData.ENTITY_LABEL, label("Verse"), "ContractMusician", "ContractPerson"), read.labels)
        assertEquals("Genesis", read.properties["book"])
    }

    @Test
    fun `an entity the object-manager repository saves is read the same by both`() {
        val id = id("verse")

        objectManager.save(entity(id, label("Verse"), "ContractMusician", properties = mapOf("book" to "Genesis", "chapter" to 1L, "source-ref" to "kjv")))

        val read = bothRead(id)
        assertEquals(setOf(NamedEntityData.ENTITY_LABEL, label("Verse"), "ContractMusician", "ContractPerson"), read.labels)
        assertEquals("Genesis", read.properties["book"])
    }

    @Test
    fun `each repository's save returns what the other would read`() {
        val byCypher = id("cypher")
        val byObjectManager = id("object-manager")

        val savedByCypher = cypher.save(entity(byCypher, label("Verse"), properties = mapOf("book" to "Genesis")))
        val savedByObjectManager = objectManager.save(entity(byObjectManager, label("Verse"), properties = mapOf("book" to "Genesis")))

        assertEquals(savedByCypher.observed(), objectManager.findById(byCypher).observed())
        assertEquals(savedByObjectManager.observed(), cypher.findById(byObjectManager).observed())
    }

    @Test
    fun `either repository can save over the other's entity, keeping its labels and properties`() {
        val first = id("cypher-first")
        val second = id("object-manager-first")

        cypher.save(entity(first, label("Musician"), properties = mapOf("born" to 1685L)))
        objectManager.save(entity(first, label("Organist"), properties = mapOf("city" to "Leipzig")))
        objectManager.save(entity(second, label("Musician"), properties = mapOf("born" to 1685L)))
        cypher.save(entity(second, label("Organist"), properties = mapOf("city" to "Leipzig")))

        listOf(first, second).forEach { id ->
            val read = bothRead(id)
            assertEquals(setOf(NamedEntityData.ENTITY_LABEL, label("Musician"), label("Organist")), read.labels)
            assertEquals(1685L, (read.properties["born"] as Number).toLong())
            assertEquals("Leipzig", read.properties["city"])
        }
    }

    @Test
    fun `an entity read from one repository can be saved through the other unchanged`() {
        val id = id("verse")
        cypher.save(entity(id, label("Verse"), properties = mapOf("book" to "Genesis")))
        val before = bothRead(id).copy(properties = bothRead(id).properties - "lastModifiedDate")

        objectManager.save(requireNotNull(cypher.findById(id)))
        cypher.save(requireNotNull(objectManager.findById(id)))

        val after = bothRead(id)
        assertEquals(before, after.copy(properties = after.properties - "lastModifiedDate"))
    }

    @Test
    fun `both find the same entities by label and by property, whoever saved them`() {
        val genesis = id("genesis")
        val exodus = id("exodus")
        cypher.save(entity(genesis, label("Verse"), properties = mapOf("book" to "Genesis")))
        objectManager.save(entity(exodus, label("Verse"), properties = mapOf("book" to "Exodus")))

        fun ids(found: List<NamedEntityData>) = found.map { it.id }.toSet()

        assertEquals(setOf(genesis, exodus), ids(cypher.findByLabel(label("Verse"))))
        assertEquals(setOf(genesis, exodus), ids(objectManager.findByLabel(label("Verse"))))
        val filter = PropertyFilter.Eq("book", "Exodus")
        assertEquals(setOf(exodus), ids(cypher.find(label("Verse"), filter)))
        assertEquals(setOf(exodus), ids(objectManager.find(label("Verse"), filter)))
    }

    @Test
    fun `a relationship either repository makes is found by both`() {
        val verse = RetrievableIdentifier(id("verse"), label("Verse"))
        val passage = RetrievableIdentifier(id("passage"), label("Passage"))
        val book = RetrievableIdentifier(id("book"), label("Book"))
        cypher.save(entity(verse.id, verse.type))
        objectManager.save(entity(passage.id, passage.type))
        cypher.save(entity(book.id, book.type))

        cypher.mergeRelationship(verse, passage, RelationshipData("IN", mapOf("position" to 1L)))
        objectManager.mergeRelationship(passage, book, RelationshipData("IN", mapOf("position" to 2L)))
        // Merging what the other made does not make a second.
        objectManager.mergeRelationship(verse, passage, RelationshipData("IN", mapOf("position" to 1L)))
        cypher.mergeRelationship(passage, book, RelationshipData("IN", mapOf("position" to 2L)))

        listOf(cypher, objectManager).forEach { repository ->
            fun related(source: RetrievableIdentifier, direction: RelationshipDirection) =
                repository.findRelated(source, "IN", direction).map { it.id }
            assertEquals(listOf(passage.id), related(verse, RelationshipDirection.OUTGOING))
            assertEquals(listOf(book.id), related(passage, RelationshipDirection.OUTGOING))
            assertEquals(listOf(verse.id), related(passage, RelationshipDirection.INCOMING))
            assertEquals(setOf(verse.id, book.id), related(passage, RelationshipDirection.BOTH).toSet())
        }
        assertEquals(
            2L,
            persistenceManager.getOne(
                QuerySpecification
                    .withStatement("MATCH (a)-[r:IN]->() WHERE a.id IN \$ids RETURN count(r)")
                    .bind(mapOf("ids" to listOf(verse.id, passage.id)))
                    .transform(Long::class.java),
            ),
        )
    }

    @Test
    fun `an entity deleted through either repository is gone from both`() {
        val first = id("first")
        val second = id("second")
        cypher.save(entity(first, label("Verse")))
        objectManager.save(entity(second, label("Verse")))

        assertTrue(objectManager.delete(first))
        assertTrue(cypher.delete(second))

        assertEquals(emptyList<NamedEntityData>(), cypher.findByLabel(label("Verse")))
        assertEquals(emptyList<NamedEntityData>(), objectManager.findByLabel(label("Verse")))
    }

    @Test
    fun `text search returns the same entities in the same order from both`() {
        // Scores are not compared: the Cypher repository divides by the best score of the result, so
        // its best hit is always 1.0; the object manager normalises each score on its own.
        cypher.save(entity(id("a"), label("Band"), name = "$tag $tag $tag quartet"))
        objectManager.save(entity(id("b"), label("Band"), name = "the $tag ensemble of the northern provinces"))
        cypher.save(entity(id("c"), label("Band"), name = "an unrelated orchestra"))

        val request = TextSimilaritySearchRequest(tag, 0.0, 10)
        val byCypher = cypher.textSearch(request).map { it.match.id }
        val byObjectManager = objectManager.textSearch(request).map { it.match.id }

        assertEquals(2, byCypher.size)
        assertEquals(byCypher, byObjectManager)
    }
}
