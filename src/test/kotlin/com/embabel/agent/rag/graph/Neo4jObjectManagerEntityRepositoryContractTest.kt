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
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/** The entity contract, held against [GraphObjectManagerEntityRepository] on Neo4j. */
@SpringBootTest(classes = [Neo4jGomStoreCharacterizationTest.Config::class])
@ActiveProfiles("neo4j")
class Neo4jObjectManagerEntityRepositoryContractTest : AbstractNamedEntityRepositoryContractTest() {

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
            gom = factory.stateless("graph"),
            properties = properties,
            dataDictionary = dictionary,
            embeddingService = embeddings,
            entitySchema = EntitySchemaProvisioner(persistenceManager, properties, { embeddings }),
        )
    }
}
