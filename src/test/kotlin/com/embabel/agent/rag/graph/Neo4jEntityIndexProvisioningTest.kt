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
import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel
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
 * The repository provisions the two entity indexes its searches bind by name.
 *
 * The regression this pins: [DrivineStore] used to be the only declaration of those indexes, and an
 * application whose primary store is [GraphObjectManagerStore] never constructs one — that store
 * models no entities, so it provisions none. Every entity search then failed against an index nobody
 * had created ("There is no such vector schema index"), while the only advice logged was to call a
 * method on a class the application does not use.
 *
 * So this test constructs the repository ALONE — no store provisions anything — and asserts both
 * indexes exist afterwards.
 */
@SpringBootTest(classes = [Neo4jEntityIndexProvisioningTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jEntityIndexProvisioningTest {

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
            graphObjectManager = factory.get("graph"),
        )
    }

    @Autowired
    lateinit var properties: GraphRagServiceProperties

    @Autowired
    @Qualifier("graph")
    lateinit var pm: PersistenceManager

    @Suppress("UNCHECKED_CAST")
    private fun indexNames(): List<String> = pm.getOne(
        QuerySpecification
            .withStatement("SHOW INDEXES YIELD name RETURN collect(name) AS names")
            .transform(List::class.java),
    ) as List<String>

    /**
     * Name *and* id. A name-only comparison cannot see a recreate — [GraphProvisioner] drops and
     * recreates an index that exists under a different name than the spec resolves, and the
     * replacement carries the same name. Neo4j allocates a fresh id, so this does see it.
     */
    @Suppress("UNCHECKED_CAST")
    private fun indexFingerprints(): Set<String> = (pm.getOne(
        QuerySpecification
            .withStatement("SHOW INDEXES YIELD name, id RETURN collect(name + ':' + toString(id)) AS names")
            .transform(List::class.java),
    ) as List<String>).toSet()

    @Test
    fun `constructing the repository creates the entity indexes it searches by name`() {
        val names = indexNames()
        assertTrue(properties.entityIndex in names, "vector index ${properties.entityIndex} in $names")
        assertTrue(
            properties.entityFullTextIndex in names,
            "full-text index ${properties.entityFullTextIndex} in $names",
        )
    }

    @Test
    fun `ensuring twice is a no-op — indexes already present are neither added nor recreated`() {
        val before = indexFingerprints()
        DrivineNamedEntityDataRepository(
            persistenceManager = pm,
            properties = properties,
            dataDictionary = DataDictionary.fromDomainTypes("test", emptyList()),
            embeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()),
        )
        assertEquals(
            before, indexFingerprints(),
            "a second construction added or recreated an index",
        )
    }

    /**
     * The BYOK sequence, which is the one that actually bites: a deployment whose embedding provider
     * credential arrives at first-run setup has no usable model while the context is coming up, so
     * the boot-time provisioning attempt is expected to fail. What must NOT happen is that the
     * failure is final — the operator enters their key, everything else lights up, and entity search
     * stays broken against an index nobody ever went back to create.
     */
    @Test
    fun `a repository built before the embedding model exists provisions once it does`() {
        // The context's own repository bean already provisioned; drop back to a virgin database so
        // this test observes ITS repository's work and not that one's. The search below restores
        // them, leaving the database as the other tests expect it.
        dropEntityIndexes()

        val model = LateInitializedEmbeddingModel()
        val repository = DrivineNamedEntityDataRepository(
            persistenceManager = pm,
            properties = properties,
            dataDictionary = DataDictionary.fromDomainTypes("test", emptyList()),
            embeddingService = SpringAiEmbeddingService("byok", "embabel", model),
        )
        // Construction survived the cold model — the context would have come up — and provisioned
        // nothing, because it could not: there is no dimension to declare a vector index with.
        assertTrue(model.dimensionsAsked > 0, "the cold model was never consulted; test proves nothing")
        assertTrue(
            properties.entityIndex !in indexNames(),
            "the cold repository created a vector index without an embedding model",
        )

        // The key arrives. The next search — not a restart — is what provisions the schema.
        model.keyArrived = true
        repository.textSearch(TextSimilaritySearchRequest("anything", 0.0, 1))

        val names = indexNames()
        assertTrue(properties.entityIndex in names, "vector index ${properties.entityIndex} in $names")
        assertTrue(
            properties.entityFullTextIndex in names,
            "full-text index ${properties.entityFullTextIndex} in $names",
        )
    }

    private fun dropEntityIndexes() {
        listOf(properties.entityIndex, properties.entityFullTextIndex).forEach { name ->
            pm.executeCypher(
                purpose = "Drop $name for a virgin-database test",
                cypher = "DROP INDEX $name IF EXISTS",
            )
        }
    }

    /**
     * An embedding model that is present as a bean but cannot answer until its provider credential
     * arrives — how a BYOK deployment looks between boot and first-run setup.
     */
    private class LateInitializedEmbeddingModel(
        var keyArrived: Boolean = false,
        private val delegate: DeterministicEmbeddingModel = DeterministicEmbeddingModel(),
    ) : EmbeddingModel by delegate {

        var dimensionsAsked = 0
            private set

        override fun dimensions(): Int {
            dimensionsAsked++
            check(keyArrived) { "No embedding provider credential yet" }
            return delegate.dimensions
        }
    }
}
