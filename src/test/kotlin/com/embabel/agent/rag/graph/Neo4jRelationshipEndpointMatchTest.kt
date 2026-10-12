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
import com.embabel.agent.rag.graph.test.DeterministicEmbeddingModel
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
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

/**
 * A relationship's endpoints are found through the entity label, so the write is an index seek.
 *
 * The endpoints used to be matched as `MATCH (from {id: $fromId}) WHERE $fromType IN labels(from)`.
 * With no label in the pattern Neo4j cannot use any index, so every relationship write scanned every
 * node in the database — twice. On a host seeding 31,000 records into a graph of a few hundred
 * thousand nodes that ran at under five writes a second, for most of two hours.
 *
 * [DrivineNamedEntityDataRepository.save] gives every entity [GraphRagServiceProperties.entityNodeName],
 * and [DrivineNamedEntityDataRepository.findById] and `delete` already find entities through it, so
 * naming it in the endpoint pattern changes no result an entity could give. The case that pins the
 * label is a node that is not an entity: it carries the type label and the id, and must not be
 * linked.
 */
@SpringBootTest(classes = [Neo4jRelationshipEndpointMatchTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jRelationshipEndpointMatchTest {

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
        fun repository(
            @Qualifier("graph") pm: PersistenceManager,
            factory: GraphObjectManagerFactory,
            properties: GraphRagServiceProperties,
        ): DrivineNamedEntityDataRepository = DrivineNamedEntityDataRepository(
            persistenceManager = pm,
            properties = properties,
            dataDictionary = DataDictionary.fromDomainTypes("test", emptyList()),
            embeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()),
            graphObjectManager = factory.stateless("graph"),
        )
    }

    @Autowired
    lateinit var repository: DrivineNamedEntityDataRepository

    @Autowired
    @Qualifier("graph")
    lateinit var pm: PersistenceManager

    @BeforeEach
    fun clear() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) WHERE n:Verse OR n:Passage DETACH DELETE n"))
    }

    private fun entity(id: String, label: String) = SimpleNamedEntityData(
        id = id,
        uri = null,
        name = id,
        description = "$label $id",
        labels = setOf(label),
        properties = emptyMap(),
        metadata = emptyMap(),
        linkedDomainType = null,
    )

    private fun count(cypher: String): Long = pm.getOne(
        QuerySpecification.withStatement(cypher).transform(Long::class.java),
    )

    private val verse = RetrievableIdentifier("verse:Gen.1.1", "Verse")
    private val passage = RetrievableIdentifier("passage:Gen.1", "Passage")
    private val inChapter = RelationshipData("IN", emptyMap())

    @Test
    fun `merging links two saved entities once, however often it runs`() {
        repository.save(entity(verse.id, verse.type))
        repository.save(entity(passage.id, passage.type))

        repository.mergeRelationship(verse, passage, inChapter)
        repository.mergeRelationship(verse, passage, inChapter)

        assertEquals(1L, count("MATCH (:Verse {id: 'verse:Gen.1.1'})-[r:IN]->(:Passage {id: 'passage:Gen.1'}) RETURN count(r)"))
    }

    @Test
    fun `creating links two saved entities`() {
        repository.save(entity(verse.id, verse.type))
        repository.save(entity(passage.id, passage.type))

        repository.createRelationship(verse, passage, inChapter)

        assertEquals(1L, count("MATCH (:Verse {id: 'verse:Gen.1.1'})-[r:IN]->(:Passage {id: 'passage:Gen.1'}) RETURN count(r)"))
    }

    @Test
    fun `a node that is not an entity is not an endpoint, even with the type label and the id`() {
        repository.save(entity(verse.id, verse.type))
        pm.execute(QuerySpecification.withStatement("CREATE (:Passage {id: 'passage:Gen.1'})"))

        repository.mergeRelationship(verse, passage, inChapter)

        assertEquals(
            0L, count("MATCH (:Verse)-[r:IN]->(:Passage) RETURN count(r)"),
            "the endpoint pattern must name the entity label — without it no index applies and every write scans the graph",
        )
    }
}
