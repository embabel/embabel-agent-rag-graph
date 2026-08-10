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
import com.embabel.common.ai.model.SpringAiEmbeddingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.schema.EnsureResult
import org.drivine.schema.IndexManager
import org.drivine.schema.SchemaItemInfo
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * The decisions [EntitySchemaProvisioner] makes before it touches a database — which need no
 * database to pin.
 */
class EntitySchemaProvisionerTest {

    private val properties = GraphRagServiceProperties()

    private val embeddingService =
        SpringAiEmbeddingService("fake", "embabel", DeterministicEmbeddingModel())

    private fun persistenceManager(type: DatabaseType): PersistenceManager =
        mockk<PersistenceManager>(relaxed = true) { every { this@mockk.type } returns type }

    /**
     * A never, not a not-yet. An engine with no schema management can never satisfy an ensure, so
     * retrying it would spend a failed round-trip on every search forever; it is refused where the
     * operator can see it, at construction.
     */
    @Test
    fun `an engine with no schema management is refused at construction`() {
        listOf(DatabaseType.NEPTUNE, DatabaseType.POSTGRES).forEach { type ->
            val e = assertThrows(DrivineException::class.java) {
                EntitySchemaProvisioner(persistenceManager(type), properties, embeddingService)
            }
            assertTrue(type.value in e.message!!, "message names the engine: ${e.message}")
            assertTrue(
                properties.entityIndex in e.message!!,
                "message names the index that cannot exist: ${e.message}",
            )
        }
    }

    @Test
    fun `schema-capable engines are accepted`() {
        listOf(DatabaseType.NEO4J, DatabaseType.MEMGRAPH, DatabaseType.FALKORDB).forEach { type ->
            assertDoesNotThrow {
                EntitySchemaProvisioner(persistenceManager(type), properties, embeddingService)
            }
        }
    }

    /**
     * `enabled = false` means "I manage the entity schema myself" — including on an engine we would
     * otherwise refuse, because the caller may be provisioning it out of band.
     */
    @Test
    fun `a disabled provisioner neither checks the engine nor touches the database`() {
        val pm = persistenceManager(DatabaseType.NEPTUNE)

        val provisioner = assertDoesNotThrow {
            EntitySchemaProvisioner(pm, properties, embeddingService, enabled = false)
        }
        provisioner.ensureOnce()

        verify(exactly = 0) { pm.indexes }
        verify(exactly = 0) { pm.constraints }
    }

    /**
     * Once an attempt succeeds the flag settles and the search path costs an atomic read — not a
     * `SHOW INDEXES` round-trip per search.
     */
    @Test
    fun `a settled provisioner stops consulting the database`() {
        val indexManager = mockk<IndexManager> {
            // Already present, under the name the spec resolves — GraphProvisioner leaves it alone.
            every { ensure(any()) } answers {
                EnsureResult.AlreadyMatching(SchemaItemInfo.fromSpec(firstArg()))
            }
        }
        val pm = mockk<PersistenceManager>(relaxed = true) {
            every { type } returns DatabaseType.NEO4J
            every { indexes } returns indexManager
        }

        val provisioner = EntitySchemaProvisioner(pm, properties, embeddingService)
        repeat(5) { provisioner.ensureOnce() }

        // One ensure per index — the vector index and the full-text index — and no more.
        verify(exactly = 2) { indexManager.ensure(any()) }
    }

    /**
     * The complement: while attempts keep failing, they keep being made, so a condition that
     * resolves later (a database that was read-only, a driver not ready) is picked up without a
     * restart.
     *
     * Note what this does NOT cover: an embedding service that answers with a placeholder dimension
     * instead of failing. That attempt *succeeds*, settles the flag, and writes an index at the
     * wrong dimension — retrying cannot help, because there is nothing left to retry. See the
     * limitation on EntitySchemaProvisioner.
     */
    @Test
    fun `a failing provisioner keeps trying`() {
        val indexManager = mockk<IndexManager> {
            every { ensure(any()) } throws DrivineException("no embedding provider credential yet")
        }
        val pm = mockk<PersistenceManager>(relaxed = true) {
            every { type } returns DatabaseType.NEO4J
            every { indexes } returns indexManager
        }

        val provisioner = EntitySchemaProvisioner(pm, properties, embeddingService)
        repeat(3) { provisioner.ensureOnce() }

        verify(exactly = 3) { indexManager.ensure(any()) }
    }
}
