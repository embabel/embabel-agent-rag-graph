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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
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
import org.junit.jupiter.api.BeforeEach
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

    /**
     * Every test starts from a database with no entity indexes and provisions what it needs, so
     * none of them can pass on work a previous one — or the context's own repository bean — left
     * behind.
     */
    @BeforeEach
    fun dropEntityIndexesBeforeEachTest() = dropEntityIndexes()

    @Test
    fun `constructing the repository creates the entity indexes it searches by name`() {
        newRepository()

        val names = indexNames()
        assertTrue(properties.entityIndex in names, "vector index ${properties.entityIndex} in $names")
        assertTrue(
            properties.entityFullTextIndex in names,
            "full-text index ${properties.entityFullTextIndex} in $names",
        )
    }

    @Test
    fun `ensuring twice is a no-op — indexes already present are neither added nor recreated`() {
        newRepository()
        val before = indexFingerprints()

        newRepository()

        assertEquals(
            before, indexFingerprints(),
            "a second construction added or recreated an index",
        )
    }

    /**
     * A deployment whose embedding provider credential arrives at first-run setup has no usable model
     * while the context is coming up, so the boot-time provisioning attempt is expected to fail. What
     * must NOT happen is that the failure is final — the operator enters their key, everything else
     * lights up, and entity search stays broken against an index nobody ever went back to create.
     *
     * This covers the variant where the unconfigured model FAILS. The variant where it answers with a
     * placeholder dimension is not covered here and is not handled by the code: that attempt succeeds
     * at the wrong dimension and settles. See the limitation on EntitySchemaProvisioner.
     */
    @Test
    fun `a repository built before the embedding model exists provisions once it does`() {
        val model = LateInitializedEmbeddingModel()
        val repository = newRepository(model)
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

    /**
     * Narrowed views are `copy()`s and carry the root's provisioner, so a search that arrives
     * through one provisions just the same. Worth pinning because it is how searches mostly arrive
     * in practice — `withContextScope(...)` — and because `narrowedBy` passes `verifyIndexes = false`,
     * which used to mean a view could do no schema work at all.
     */
    @Test
    fun `a search through a narrowed view provisions too`() {
        val model = LateInitializedEmbeddingModel()
        val repository = newRepository(model)
        assertTrue(properties.entityIndex !in indexNames(), "nothing provisioned while the model was cold")

        model.keyArrived = true
        repository.withContextScope("some-context").textSearch(TextSimilaritySearchRequest("anything", 0.0, 1))

        val names = indexNames()
        assertTrue(properties.entityIndex in names, "vector index ${properties.entityIndex} in $names")
        assertTrue(
            properties.entityFullTextIndex in names,
            "full-text index ${properties.entityFullTextIndex} in $names",
        )
    }

    /**
     * The vector path provisions as well as the text path — and ordering matters: the ensure runs
     * before the query embedding, so a cold model fails at the embed rather than leaving a
     * half-provisioned schema behind.
     */
    @Test
    fun `the vector search path provisions too`() {
        val model = LateInitializedEmbeddingModel()
        val repository = newRepository(model)
        assertTrue(properties.entityIndex !in indexNames(), "nothing provisioned while the model was cold")

        model.keyArrived = true
        repository.vectorSearch(TextSimilaritySearchRequest("anything", 0.0, 1))

        assertTrue(properties.entityIndex in indexNames(), "the vector search provisioned its own index")
    }

    /**
     * `verifyIndexes = false` is the escape hatch for a caller that provisions the entity schema
     * itself. It has to hold on the search path too, or it is not an escape hatch.
     */
    @Test
    fun `verifyIndexes false provisions nothing, at construction or on search`() {
        val repository = newRepository(verifyIndexes = false)
        assertTrue(properties.entityIndex !in indexNames(), "construction provisioned despite opting out")

        // The search itself fails — there is no full-text index, which is the caller's business
        // under this flag. What matters is that attempting it did not quietly create one.
        runCatching { repository.textSearch(TextSimilaritySearchRequest("anything", 0.0, 1)) }

        assertTrue(
            properties.entityFullTextIndex !in indexNames(),
            "the search provisioned despite opting out",
        )
    }

    /**
     * The BYOK shape as the platform actually produces it. The cold-model tests above use a service
     * that THROWS, which was the old failure mode; a keyless deployment now resolves a placeholder
     * that answers `awaitingKey` and refuses to report a dimension. Nothing may be provisioned from
     * it, and — the part a throwing double cannot show — nothing may be provisioned by catching it
     * either, since the placeholder is asked, never called.
     */
    @Test
    fun `a placeholder embedding service provisions nothing, and a real one then does`() {
        val placeholder = PlaceholderEmbeddingService()
        var resolved: EmbeddingService = placeholder
        val repository = DrivineNamedEntityDataRepository(
            persistenceManager = pm,
            properties = properties,
            dataDictionary = DataDictionary.fromDomainTypes("test", emptyList()),
            embeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()),
            entitySchema = EntitySchemaProvisioner(pm, properties, { resolved }),
        )
        assertEquals(
            0, placeholder.dimensionReads,
            "read a dimension from a placeholder — an absent index proves nothing on its own, " +
                "since the read would have thrown and been caught",
        )
        assertTrue(
            properties.entityIndex !in indexNames(),
            "provisioned while the deployment was still awaiting an embedding key",
        )

        resolved = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel())
        repository.textSearch(TextSimilaritySearchRequest("anything", 0.0, 1))

        val names = indexNames()
        assertTrue(properties.entityIndex in names, "vector index ${properties.entityIndex} in $names")
        assertTrue(
            properties.entityFullTextIndex in names,
            "full-text index ${properties.entityFullTextIndex} in $names",
        )
    }

    /** What `embabel.models.default-embedding-model=setup-required-embedding` resolves to. */
    private class PlaceholderEmbeddingService : EmbeddingService {
        var dimensionReads = 0
            private set

        override val name = "setup-required-embedding"
        override val provider = "none"
        override val pricingModel: PricingModel? = null
        override val awaitingKey = true
        override fun embed(text: String): FloatArray = error("no embedding service configured")
        override fun embed(texts: List<String>): List<FloatArray> = error("no embedding service configured")
        override val dimensions: Int
            get() {
                dimensionReads++
                error("no embedding service configured")
            }
    }

    private fun newRepository(
        model: EmbeddingModel = DeterministicEmbeddingModel(),
        verifyIndexes: Boolean = true,
    ) = DrivineNamedEntityDataRepository(
        persistenceManager = pm,
        properties = properties,
        dataDictionary = DataDictionary.fromDomainTypes("test", emptyList()),
        embeddingService = SpringAiEmbeddingService("fake", "embabel", model),
        verifyIndexes = verifyIndexes,
    )

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
