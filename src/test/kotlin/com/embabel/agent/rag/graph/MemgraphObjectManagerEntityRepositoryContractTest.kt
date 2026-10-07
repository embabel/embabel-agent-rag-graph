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
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/** The entity contract, held against [GraphObjectManagerEntityRepository] on Memgraph. */
@SpringBootTest(classes = [MemgraphObjectManagerEntityRepositoryContractTest.Config::class])
@Testcontainers
@ActiveProfiles("memgraph")
class MemgraphObjectManagerEntityRepositoryContractTest : AbstractNamedEntityRepositoryContractTest() {

    companion object {
        @Container
        @JvmStatic
        val memgraph: GenericContainer<*> = GenericContainer(
            DockerImageName.parse("memgraph/memgraph-mage:latest")
        )
            .withExposedPorts(7687)
            .withCommand("--also-log-to-stderr", "--log-level=WARNING")
    }

    @Configuration
    @Profile("memgraph")
    @EnableDrivine
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableConfigurationProperties(GraphRagServiceProperties::class)
    class Config {
        @Bean
        @Primary
        fun dataSourceMap(): DataSourceMap = DataSourceMap(
            mapOf(
                "graph" to ConnectionProperties(
                    host = memgraph.host,
                    port = memgraph.getMappedPort(7687),
                    userName = "",
                    password = "",
                    type = DatabaseType.MEMGRAPH,
                    databaseName = "memgraph",
                )
            )
        )

        @Bean("graph")
        fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("graph")
    }

    @Autowired
    @Qualifier("graph")
    override lateinit var persistenceManager: PersistenceManager

    @Autowired
    lateinit var factory: GraphObjectManagerFactory

    @Autowired
    lateinit var properties: GraphRagServiceProperties

    override val repository: NamedEntityDataRepository by lazy {
        val embeddings = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel())
        GraphObjectManagerEntityRepository(
            gom = factory.get("graph"),
            properties = properties,
            dataDictionary = dictionary,
            embeddingService = embeddings,
            entitySchema = EntitySchemaProvisioner(persistenceManager, properties, { embeddings }),
        )
    }

    // See AbstractRagSearchCharacterizationTest.cleansUpByDeletion: Memgraph's vector index keeps deleted nodes.
    override val cleansUpByDeletion = false
}
