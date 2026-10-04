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
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.RelationshipDirection
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** A dictionary type with an ancestor, so a save can be seen to write the ancestor's label too. */
interface ContractPerson : NamedEntity

interface ContractMusician : ContractPerson

/**
 * What a [NamedEntityDataRepository] does, stated against the interface so that every implementation
 * on every engine is held to the same behaviour.
 *
 * Each test writes under labels and ids unique to that test, so nothing depends on a clean database
 * and nothing is disturbed by another test's leftovers.
 */
abstract class AbstractNamedEntityRepositoryContractTest {

    /** The repository under test, built over [dictionary]. */
    protected abstract val repository: NamedEntityDataRepository

    /** The persistence manager for the same database, used to arrange and to inspect, never to assert through. */
    protected abstract val persistenceManager: PersistenceManager

    /** See [AbstractRagSearchCharacterizationTest.cleansUpByDeletion]. */
    protected open val cleansUpByDeletion: Boolean = true

    protected val dictionary: DataDictionary =
        DataDictionary.fromClasses("contract", ContractPerson::class.java, ContractMusician::class.java)

    private lateinit var tag: String
    private val savedIds = mutableListOf<String>()

    @BeforeEach
    fun newTag() {
        tag = "t${UUID.randomUUID().toString().replace("-", "").take(10)}"
        savedIds.clear()
    }

    @AfterEach
    fun removeSaved() {
        if (!cleansUpByDeletion || savedIds.isEmpty()) return
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.id IN \$ids DETACH DELETE n")
                .bind(mapOf("ids" to savedIds.toList())),
        )
    }

    private fun label(name: String) = "$name$tag"

    private fun id(name: String) = "$name-$tag".also { savedIds += it }

    private fun entity(
        id: String,
        vararg labels: String,
        name: String = id,
        description: String = "about $id",
        properties: Map<String, Any> = emptyMap(),
    ): NamedEntityData = SimpleNamedEntityData(
        id = id,
        uri = null,
        name = name,
        description = description,
        labels = labels.toSet(),
        properties = properties,
        metadata = emptyMap(),
        linkedDomainType = null,
    )

    private fun <T : Any> present(value: T?): T = requireNotNull(value) { "expected a stored entity, found none" }

    private fun count(cypher: String, params: Map<String, Any?> = emptyMap()): Long = persistenceManager.getOne(
        QuerySpecification.withStatement(cypher).bind(params).transform(Long::class.java),
    )

    // --- save and read ---------------------------------------------------------------------

    @Test
    fun `a saved entity is read back by id with its name, description, labels and properties`() {
        val id = id("verse")
        repository.save(
            entity(id, label("Verse"), name = "Genesis 1:1", description = "the first verse", properties = mapOf("book" to "Genesis", "chapter" to 1L)),
        )

        val found = present(repository.findById(id))

        assertEquals("Genesis 1:1", found.name)
        assertEquals("the first verse", found.description)
        assertTrue(label("Verse") in found.labels(), "the entity's own label: ${found.labels()}")
        assertTrue(NamedEntityData.ENTITY_LABEL in found.labels(), "the entity label every saved entity carries: ${found.labels()}")
        assertEquals("Genesis", found.properties["book"])
        assertEquals(1L, (found.properties["chapter"] as Number).toLong())
    }

    @Test
    fun `save returns the entity as stored`() {
        val id = id("verse")

        val saved = repository.save(entity(id, label("Verse"), properties = mapOf("book" to "Genesis")))

        assertEquals(id, saved.id)
        assertTrue(label("Verse") in saved.labels())
        assertEquals("Genesis", saved.properties["book"])
    }

    @Test
    fun `an id that was never saved is not found`() {
        assertNull(repository.findById(id("missing")))
    }

    @Test
    fun `saving an id again updates the one node`() {
        val id = id("verse")
        repository.save(entity(id, label("Verse"), name = "first", properties = mapOf("book" to "Genesis")))

        repository.save(entity(id, label("Verse"), name = "second", properties = mapOf("book" to "Exodus")))

        assertEquals(1L, count("MATCH (n {id: \$id}) RETURN count(n)", mapOf("id" to id)))
        val found = present(repository.findById(id))
        assertEquals("second", found.name)
        assertEquals("Exodus", found.properties["book"])
    }

    @Test
    fun `saving an id again leaves a property the second save does not mention`() {
        val id = id("verse")
        repository.save(entity(id, label("Verse"), properties = mapOf("book" to "Genesis")))

        repository.save(entity(id, label("Verse"), properties = mapOf("chapter" to 1L)))

        val found = present(repository.findById(id))
        assertEquals("Genesis", found.properties["book"])
        assertEquals(1L, (found.properties["chapter"] as Number).toLong())
    }

    @Test
    fun `saving an id again under another label keeps both labels on the one node`() {
        val id = id("artist")
        repository.save(entity(id, label("Musician")))

        repository.save(entity(id, label("Painter")))

        assertEquals(1L, count("MATCH (n {id: \$id}) RETURN count(n)", mapOf("id" to id)))
        val labels = present(repository.findById(id)).labels()
        assertTrue(label("Musician") in labels && label("Painter") in labels, "labels: $labels")
    }

    @Test
    fun `a label the dictionary knows is saved with its ancestors' labels`() {
        val id = id("artist")

        repository.save(entity(id, "ContractMusician"))

        val labels = present(repository.findById(id)).labels()
        assertTrue("ContractMusician" in labels && "ContractPerson" in labels, "labels: $labels")
    }

    @Test
    fun `a property whose name is not a plain identifier is stored under that name`() {
        val id = id("verse")

        repository.save(entity(id, label("Verse"), properties = mapOf("source-ref" to "kjv", "osis id" to "Gen.1.1")))

        val found = present(repository.findById(id))
        assertEquals("kjv", found.properties["source-ref"])
        assertEquals("Gen.1.1", found.properties["osis id"])
    }

    @Test
    fun `a node that is not an entity is not found by id`() {
        val id = id("stray")
        persistenceManager.execute(
            QuerySpecification.withStatement("CREATE (:${label("Verse")} {id: \$id, name: 'stray'})").bind(mapOf("id" to id)),
        )

        assertNull(repository.findById(id))
    }

    // --- delete ----------------------------------------------------------------------------

    @Test
    fun `deleting a saved entity removes it and says so`() {
        val id = id("verse")
        repository.save(entity(id, label("Verse")))

        assertTrue(repository.delete(id))

        assertNull(repository.findById(id))
    }

    @Test
    fun `deleting an id that was never saved says nothing was deleted`() {
        assertFalse(repository.delete(id("missing")))
    }

    @Test
    fun `deleting an entity removes its relationships with it`() {
        val verse = id("verse")
        val passage = id("passage")
        repository.save(entity(verse, label("Verse")))
        repository.save(entity(passage, label("Passage")))
        repository.createRelationship(
            RetrievableIdentifier(verse, label("Verse")), RetrievableIdentifier(passage, label("Passage")), RelationshipData("IN"),
        )

        assertTrue(repository.delete(verse))

        assertNotNull(repository.findById(passage))
        assertEquals(0L, count("MATCH ({id: \$id})-[r]-() RETURN count(r)", mapOf("id" to passage)))
    }

    // --- find by label ---------------------------------------------------------------------

    @Test
    fun `entities are found by a label they carry`() {
        val one = id("one")
        val two = id("two")
        val other = id("other")
        repository.save(entity(one, label("Verse")))
        repository.save(entity(two, label("Verse")))
        repository.save(entity(other, label("Passage")))

        assertEquals(setOf(one, two), repository.findByLabel(label("Verse")).map { it.id }.toSet())
    }

    @Test
    fun `a node carrying the label that is not an entity is not found by label`() {
        val saved = id("saved")
        val stray = id("stray")
        repository.save(entity(saved, label("Verse")))
        persistenceManager.execute(
            QuerySpecification.withStatement("CREATE (:${label("Verse")} {id: \$id, name: 'stray'})").bind(mapOf("id" to stray)),
        )

        assertEquals(setOf(saved), repository.findByLabel(label("Verse")).map { it.id }.toSet())
    }

    @Test
    fun `entities are found by label and a property filter`() {
        val genesis = id("genesis")
        val exodus = id("exodus")
        repository.save(entity(genesis, label("Verse"), properties = mapOf("book" to "Genesis")))
        repository.save(entity(exodus, label("Verse"), properties = mapOf("book" to "Exodus")))

        val found = repository.find(label("Verse"), PropertyFilter.Eq("book", "Genesis"))

        assertEquals(setOf(genesis), found.map { it.id }.toSet())
    }

    @Test
    fun `entities are found by any of several labels, each once`() {
        val verse = id("verse")
        val passage = id("passage")
        val both = id("both")
        val other = id("other")
        repository.save(entity(verse, label("Verse")))
        repository.save(entity(passage, label("Passage")))
        repository.save(entity(both, label("Verse"), label("Passage")))
        repository.save(entity(other, label("Book")))

        val found = repository.find(EntityFilter.HasAnyLabel(setOf(label("Verse"), label("Passage")))).map { it.id }

        assertEquals(listOf(both, passage, verse), found.sorted())
    }

    // --- relationships ---------------------------------------------------------------------

    private fun savedPair(): Pair<RetrievableIdentifier, RetrievableIdentifier> {
        val verse = RetrievableIdentifier(id("verse"), label("Verse"))
        val passage = RetrievableIdentifier(id("passage"), label("Passage"))
        repository.save(entity(verse.id, verse.type))
        repository.save(entity(passage.id, passage.type))
        return verse to passage
    }

    private fun relationships(from: RetrievableIdentifier, to: RetrievableIdentifier, type: String): Long = count(
        "MATCH ({id: \$from})-[r:$type]->({id: \$to}) RETURN count(r)", mapOf("from" to from.id, "to" to to.id),
    )

    @Test
    fun `creating a relationship links the two entities in the direction given`() {
        val (verse, passage) = savedPair()

        repository.createRelationship(verse, passage, RelationshipData("IN"))

        assertEquals(1L, relationships(verse, passage, "IN"))
        assertEquals(0L, relationships(passage, verse, "IN"))
    }

    @Test
    fun `creating a relationship twice makes two`() {
        val (verse, passage) = savedPair()

        repository.createRelationship(verse, passage, RelationshipData("IN"))
        repository.createRelationship(verse, passage, RelationshipData("IN"))

        assertEquals(2L, relationships(verse, passage, "IN"))
    }

    @Test
    fun `merging a relationship links the two entities once, however often it runs`() {
        val (verse, passage) = savedPair()

        repository.mergeRelationship(verse, passage, RelationshipData("IN"))
        repository.mergeRelationship(verse, passage, RelationshipData("IN"))

        assertEquals(1L, relationships(verse, passage, "IN"))
    }

    @Test
    fun `a relationship carries its properties, and a merge updates them`() {
        val (verse, passage) = savedPair()

        repository.mergeRelationship(verse, passage, RelationshipData("IN", mapOf("position" to 1L, "source-ref" to "kjv")))
        repository.mergeRelationship(verse, passage, RelationshipData("IN", mapOf("position" to 2L)))

        val params = mapOf("from" to verse.id, "to" to passage.id)
        assertEquals(2L, count("MATCH ({id: \$from})-[r:IN]->({id: \$to}) RETURN r.position", params))
        assertEquals(1L, count("MATCH ({id: \$from})-[r:IN]->({id: \$to}) WHERE r.`source-ref` = 'kjv' RETURN count(r)", params))
    }

    @Test
    fun `a relationship is not made to an endpoint that lacks the stated type`() {
        val (verse, passage) = savedPair()

        repository.mergeRelationship(verse, RetrievableIdentifier(passage.id, label("Book")), RelationshipData("IN"))

        assertEquals(0L, relationships(verse, passage, "IN"))
    }

    @Test
    fun `a node that is not an entity is not an endpoint, even with the type label and the id`() {
        val verse = RetrievableIdentifier(id("verse"), label("Verse"))
        val stray = RetrievableIdentifier(id("stray"), label("Passage"))
        repository.save(entity(verse.id, verse.type))
        persistenceManager.execute(
            QuerySpecification.withStatement("CREATE (:${stray.type} {id: \$id})").bind(mapOf("id" to stray.id)),
        )

        repository.mergeRelationship(verse, stray, RelationshipData("IN"))

        assertEquals(0L, relationships(verse, stray, "IN"))
    }

    @Test
    fun `related entities are found in the direction asked for`() {
        val (verse, passage) = savedPair()
        repository.createRelationship(verse, passage, RelationshipData("IN"))

        fun related(source: RetrievableIdentifier, direction: RelationshipDirection) =
            repository.findRelated(source, "IN", direction).map { it.id }

        assertEquals(listOf(passage.id), related(verse, RelationshipDirection.OUTGOING))
        assertEquals(emptyList<String>(), related(verse, RelationshipDirection.INCOMING))
        assertEquals(listOf(verse.id), related(passage, RelationshipDirection.INCOMING))
        assertEquals(listOf(passage.id), related(verse, RelationshipDirection.BOTH))
        assertEquals(listOf(verse.id), related(passage, RelationshipDirection.BOTH))
    }

    @Test
    fun `related entities are found only over the relationship named`() {
        val (verse, passage) = savedPair()
        repository.createRelationship(verse, passage, RelationshipData("QUOTES"))

        assertEquals(emptyList<NamedEntityData>(), repository.findRelated(verse, "IN", RelationshipDirection.OUTGOING))
    }

    // --- search ----------------------------------------------------------------------------

    @Test
    fun `text search finds an entity by a word in its name`() {
        val id = id("quartet")
        val other = id("other")
        repository.save(entity(id, label("Band"), name = "The $tag Quartet", description = "a string quartet"))
        repository.save(entity(other, label("Band"), name = "Another Band", description = "unrelated"))

        val found = repository.textSearch(TextSimilaritySearchRequest(tag, 0.0, 10))

        assertEquals(listOf(id), found.map { it.match.id })
        assertTrue(label("Band") in found.single().match.labels())
    }

    @Test
    fun `text search finds an entity by a word in its description`() {
        val id = id("quartet")
        repository.save(entity(id, label("Band"), name = "A Quartet", description = "known for $tag"))

        assertEquals(listOf(id), repository.textSearch(TextSimilaritySearchRequest(tag, 0.0, 10)).map { it.match.id })
    }

    @Test
    fun `text search honours a property filter`() {
        val kept = id("kept")
        val dropped = id("dropped")
        repository.save(entity(kept, label("Band"), name = "$tag one", properties = mapOf("city" to "Vienna")))
        repository.save(entity(dropped, label("Band"), name = "$tag two", properties = mapOf("city" to "Berlin")))

        val found = repository.textSearch(
            TextSimilaritySearchRequest(tag, 0.0, 10), metadataFilter = PropertyFilter.Eq("city", "Vienna"),
        )

        assertEquals(listOf(kept), found.map { it.match.id })
    }

    @Test
    fun `text search honours an entity filter on labels`() {
        val kept = id("kept")
        val dropped = id("dropped")
        repository.save(entity(kept, label("Band"), name = "$tag one"))
        repository.save(entity(dropped, label("Venue"), name = "$tag two"))

        val found = repository.textSearch(
            TextSimilaritySearchRequest(tag, 0.0, 10), entityFilter = EntityFilter.HasAnyLabel(setOf(label("Band"))),
        )

        assertEquals(listOf(kept), found.map { it.match.id })
    }

    @Test
    fun `vector search runs against the entity index`() {
        repository.save(entity(id("quartet"), label("Band"), name = "The $tag Quartet"))

        // No write path gives an entity an embedding yet, so there is nothing for this to return.
        // What is held here is that the search reaches the index and answers.
        repository.vectorSearch(TextSimilaritySearchRequest("string quartet", 0.0, 10))
    }

    // --- context scope ---------------------------------------------------------------------

    @Test
    fun `a context-scoped repository returns only entities a proposition in that context mentions`() {
        val mentioned = id("mentioned")
        val elsewhere = id("elsewhere")
        val unmentioned = id("unmentioned")
        listOf(mentioned, elsewhere, unmentioned).forEach { repository.save(entity(it, label("Contact"))) }
        mapOf(mentioned to "ctx-$tag", elsewhere to "other-$tag").forEach { (entityId, contextId) ->
            val propositionId = id("proposition-$entityId")
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(
                        """
                        MATCH (e {id: ${'$'}entityId})
                        CREATE (:Proposition {id: ${'$'}propositionId, contextId: ${'$'}contextId})-[:MENTIONS]->(e)
                        """.trimIndent(),
                    )
                    .bind(mapOf("entityId" to entityId, "propositionId" to propositionId, "contextId" to contextId)),
            )
        }

        val scoped = repository.withContextScope("ctx-$tag")

        assertEquals(listOf(mentioned), scoped.findByLabel(label("Contact")).map { it.id })
        assertNotNull(scoped.findById(mentioned))
        assertNull(scoped.findById(unmentioned))
    }
}
