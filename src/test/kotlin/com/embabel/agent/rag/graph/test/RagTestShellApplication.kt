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

import com.embabel.agent.config.annotation.EnableAgents
import com.embabel.agent.rag.graph.GraphRagServiceProperties
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Spring Shell application for testing RAG functionality interactively.
 *
 * This is a cut-down version of the Guide application for debugging and testing
 * the RAG store.
 *
 * Run this app to get an interactive shell where you can:
 * - Execute RAG searches against a pre-seeded Neo4j database
 * - Use the 'chat' command to enter an interactive chat mode with RAG-powered responses
 *
 * Configuration via environment variables:
 *   - NEO4J_URI: bolt://localhost:7687 (default)
 *   - NEO4J_USERNAME: neo4j (default)
 *   - NEO4J_PASSWORD: brahmsian (default)
 *   - OPENAI_API_KEY: required for embeddings and chat
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAgents(loggingTheme = "starwars")
@ComponentScan(
    basePackages = [
        "org.drivine",
        "com.embabel.agent.rag.graph",
        "com.embabel.agent.rag.graph.test"
    ],
    excludeFilters = [
        ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
            classes = [TestAppContext::class]
        )
    ]
)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(GraphRagServiceProperties::class)
class RagTestShellApplication {

    @Bean
    fun fakeUser(): FakeUser = FakeUser.DEFAULT

    @Bean
    fun dataSourceMap(): DataSourceMap {
        val uri = System.getenv("NEO4J_URI") ?: "bolt://localhost:7687"
        val username = System.getenv("NEO4J_USERNAME") ?: "neo4j"
        val password = System.getenv("NEO4J_PASSWORD") ?: "brahmsian"

        val host = uri.substringAfter("bolt://").substringBefore(":")
        val port = uri.substringAfter("bolt://").substringAfter(":").toIntOrNull() ?: 7687

        val neo4jProperties = ConnectionProperties(
            host = host,
            port = port,
            userName = username,
            password = password,
            type = DatabaseType.NEO4J,
            databaseName = "neo4j"
        )
        return DataSourceMap(mapOf("graph" to neo4jProperties))
    }

    @Bean("graph")
    fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager {
        return factory.get("graph")
    }
}

fun main(args: Array<String>) {
    SpringApplication.run(RagTestShellApplication::class.java, *args)
}
