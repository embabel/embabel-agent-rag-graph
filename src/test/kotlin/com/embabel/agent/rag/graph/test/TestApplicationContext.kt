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
package com.embabel.agent.rag.graph.test

import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.graph.DrivineCypherSearch
import com.embabel.agent.rag.graph.DrivineStore
import com.embabel.agent.rag.graph.GraphRagServiceProperties
import com.embabel.agent.rag.graph.dialect.Neo4jRagDialect
import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.embabel.common.util.generateRandomFloatArray
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.FilterType
import org.springframework.transaction.PlatformTransactionManager
import java.util.*

/**
 * Test configuration for Drivine-based tests.
 *
 * Uses @EnableDrivine and @EnableDrivineTestConfig which handle:
 * - Testcontainers (default): starts Neo4j container automatically
 * - Local Neo4j: set USE_LOCAL_NEO4J=true env var or test.neo4j.use-local=true property
 *
 * Note: Do NOT use @EnableDrivinePropertiesConfig with @EnableDrivineTestConfig - the test
 * config provides its own DataSourceMap with testcontainer support.
 *
 * Datasource properties are configured in application.yml under database.datasources.graph
 */
@Configuration
@EnableDrivine
@EnableDrivineTestConfig
@ComponentScan(
    basePackages = ["com.embabel.agent.rag.graph"],
    excludeFilters = [ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = [
            RagTestShellApplication::class,
            RagShellCommands::class,
            RagChatActions::class,
            DrivineCypherSearch::class,
            DrivineStore::class,
        ]
    )]
)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(GraphRagServiceProperties::class)
class TestAppContext {

    @Bean("graph")
    fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager {
        return factory.get("graph")
    }

    @Bean
    fun drivineCypherSearch(persistenceManager: PersistenceManager): DrivineCypherSearch {
        return DrivineCypherSearch(persistenceManager)
    }

    @Bean
    fun drivineStore(
        persistenceManager: PersistenceManager,
        properties: GraphRagServiceProperties,
        transactionManager: PlatformTransactionManager,
        cypherSearch: DrivineCypherSearch
    ): DrivineStore {
        return DrivineStore(
            persistenceManager = persistenceManager,
            properties = properties,
            chunkerConfig = ContentChunker.Config(),
            chunkTransformer = ChunkTransformer.NO_OP,
            platformTransactionManager = transactionManager,
            cypherSearch = cypherSearch,
            dialect = Neo4jRagDialect(),
            embeddingService = SpringAiEmbeddingService("fake", "embabel", FakeEmbeddingModel())
        )
    }
}

data class FakeEmbeddingModel(
    val dimensions: Int = 1536,
) : EmbeddingModel {

    override fun embed(document: Document): FloatArray {
        return generateRandomFloatArray(dimensions)
    }

    override fun embed(texts: List<String>): MutableList<FloatArray> {
        return texts.map { generateRandomFloatArray(dimensions) }.toMutableList()
    }

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        val output = LinkedList<Embedding>()
        for (i in request.instructions.indices) {
            output.add(Embedding(generateRandomFloatArray(dimensions), i))
        }
        return EmbeddingResponse(output)
    }
}
