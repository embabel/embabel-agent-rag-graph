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

import com.embabel.agent.rag.graph.dialect.FalkorDbRagDialect
import com.embabel.agent.rag.graph.test.DeterministicEmbeddingModel
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager

/**
 * FalkorDB binding of [AbstractRagSearchCharacterizationTest]. Wiring mirrors
 * [com.embabel.agent.rag.graph.dialect.FalkorDbIngestionTest] (profile + application-falkordb.yml).
 */
@SpringBootTest(classes = [FalkorDbRagSearchCharacterizationTest.Config::class])
@ActiveProfiles("falkordb")
class FalkorDbRagSearchCharacterizationTest : AbstractRagSearchCharacterizationTest() {

    @Configuration
    @Profile("falkordb")
    @EnableDrivine
    @EnableDrivineTestConfig
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableConfigurationProperties(GraphRagServiceProperties::class)
    class Config {
        @Bean("graph")
        fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager =
            factory.get("graph")

        @Bean
        fun drivineCypherSearch(persistenceManager: PersistenceManager): DrivineCypherSearch =
            DrivineCypherSearch(persistenceManager)

        @Bean
        fun drivineStore(
            persistenceManager: PersistenceManager,
            properties: GraphRagServiceProperties,
            transactionManager: PlatformTransactionManager,
            cypherSearch: DrivineCypherSearch,
        ): DrivineStore = DrivineStore(
            persistenceManager = persistenceManager,
            properties = properties,
            chunkerConfig = ContentChunker.Config(),
            chunkTransformer = ChunkTransformer.NO_OP,
            platformTransactionManager = transactionManager,
            cypherSearch = cypherSearch,
            dialect = FalkorDbRagDialect(),
            embeddingService = SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel()),
        )
    }

    @Autowired
    lateinit var drivineStore: DrivineStore

    override val store: RagStoreUnderTest get() = DrivineRagStoreAdapter(drivineStore)

    @Autowired
    @Qualifier("graph")
    override lateinit var persistenceManager: PersistenceManager

    override val engineName = "FalkorDB"
}
