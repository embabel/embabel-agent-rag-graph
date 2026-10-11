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
import org.drivine.test.OracleCypherFixtures
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@SpringBootTest(classes = [OracleGomStoreCharacterizationTest.Config::class])
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS", matches = "true")
@ActiveProfiles("oracle")
class OracleGomStoreCharacterizationTest : AbstractRagSearchCharacterizationTest() {

    @Configuration
    @Profile("oracle")
    @EnableDrivine
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableConfigurationProperties(GraphRagServiceProperties::class)
    class Config {
        @Bean
        @Primary
        fun dataSourceMap(): DataSourceMap = DataSourceMap(
            mapOf(
                "graph" to ConnectionProperties(
                    host = OracleTestContainer.getConnectionHost(),
                    port = OracleTestContainer.getConnectionPort(),
                    userName = OracleTestContainer.getConnectionUsername(),
                    password = OracleTestContainer.getConnectionPassword(),
                    type = DatabaseType.ORACLE,
                    databaseName = OracleTestContainer.SERVICE,
                )
            )
        )

        @Bean("graph")
        fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("graph")

        @Bean
        fun gomStore(
            factory: GraphObjectManagerFactory,
            persistenceManager: PersistenceManager,
            properties: GraphRagServiceProperties,
        ): GraphObjectManagerStore = GraphObjectManagerStore(
            gom = factory.stateless("graph"),
            persistenceManager = persistenceManager,
            properties = properties,
            chunkerConfig = ContentChunker.Config(),
            chunkTransformer = ChunkTransformer.NO_OP,
            embeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()),
        )
    }

    @Autowired
    lateinit var gomStore: GraphObjectManagerStore

    @Autowired
    @Qualifier("graph")
    lateinit var oracle: PersistenceManager

    // The contract's fixtures are Cypher, which Oracle does not speak: they are worked out over the graph.
    override val persistenceManager: PersistenceManager by lazy { OracleCypherFixtures.over(oracle) }

    override val store: RagStoreUnderTest get() = GomRagStoreAdapter(gomStore)

    override val engineName = "Oracle"

}
