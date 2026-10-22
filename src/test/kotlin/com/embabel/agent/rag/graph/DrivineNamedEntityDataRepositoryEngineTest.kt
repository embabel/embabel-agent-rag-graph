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
import com.embabel.common.ai.model.EmbeddingService
import io.mockk.every
import io.mockk.mockk
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Every statement of [DrivineNamedEntityDataRepository] is Cypher, so it is refused at construction
 * on an engine that does not speak it, rather than failing on its first statement.
 */
class DrivineNamedEntityDataRepositoryEngineTest {

    private fun repositoryOn(type: DatabaseType): DrivineNamedEntityDataRepository {
        val pm = mockk<PersistenceManager>(relaxed = true)
        every { pm.type } returns type
        return DrivineNamedEntityDataRepository(
            persistenceManager = pm,
            properties = GraphRagServiceProperties(),
            dataDictionary = mockk<DataDictionary>(relaxed = true),
            embeddingService = mockk<EmbeddingService>(relaxed = true),
            verifyIndexes = false,
        )
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType::class, names = ["ORACLE", "POSTGRES"])
    fun `is refused on an engine that does not speak Cypher, naming the engine`(type: DatabaseType) {
        val e = assertThrows<UnsupportedOperationException> { repositoryOn(type) }
        assertTrue(e.message.orEmpty().contains(type.toString()), e.message)
        assertTrue(e.message.orEmpty().contains("GraphObjectManagerEntityRepository"), e.message)
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType::class, names = ["NEO4J", "FALKORDB", "MEMGRAPH"])
    fun `is built on an engine that speaks Cypher`(type: DatabaseType) {
        assertDoesNotThrow { repositoryOn(type) }
    }
}
