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
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `ensuring twice is a no-op — indexes already present are left alone`() {
        val before = indexNames()
        DrivineNamedEntityDataRepository(
            persistenceManager = pm,
            properties = properties,
            dataDictionary = DataDictionary.fromDomainTypes("test", emptyList()),
            embeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()),
        )
        assertTrue(
            indexNames().toSet() == before.toSet(),
            "a second construction changed the index set: ${before.toSet()} -> ${indexNames().toSet()}",
        )
    }
}
