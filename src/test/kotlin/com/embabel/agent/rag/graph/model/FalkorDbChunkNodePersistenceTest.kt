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
package com.embabel.agent.rag.graph.model

import com.embabel.agent.rag.graph.GraphRagServiceProperties
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManager
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
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(classes = [FalkorDbChunkNodePersistenceTest.Config::class])
@ActiveProfiles("falkordb")
class FalkorDbChunkNodePersistenceTest : AbstractChunkNodePersistenceTest() {

    @Configuration
    @Profile("falkordb")
    @EnableDrivine
    @EnableDrivineTestConfig
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableConfigurationProperties(GraphRagServiceProperties::class)
    class Config {
        @Bean("graph")
        fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("graph")

        @Bean
        fun graphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager = factory.get("graph")
    }

    @Autowired
    override lateinit var gom: GraphObjectManager

    @Autowired
    @Qualifier("graph")
    override lateinit var persistenceManager: PersistenceManager

    override val engineName = "FalkorDB"
}
