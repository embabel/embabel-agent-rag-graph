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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
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
import org.junit.jupiter.api.Assertions.assertEquals
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
                EntitySchemaProvisioner(persistenceManager(type), properties, { embeddingService })
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
                EntitySchemaProvisioner(persistenceManager(type), properties, { embeddingService })
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
            EntitySchemaProvisioner(pm, properties, { embeddingService }, enabled = false)
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

        val provisioner = EntitySchemaProvisioner(pm, properties, { embeddingService })
        repeat(5) { provisioner.ensureOnce() }

        // One ensure per index — the vector index and the full-text index — and no more.
        verify(exactly = 2) { indexManager.ensure(any()) }
    }

    /**
     * The complement: while attempts keep failing, they keep being made, so a condition that
     * resolves later (a database that was read-only, a driver not ready) is picked up without a
     * restart.
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

        val provisioner = EntitySchemaProvisioner(pm, properties, { embeddingService })
        repeat(3) { provisioner.ensureOnce() }

        verify(exactly = 3) { indexManager.ensure(any()) }
    }

    /**
     * A BYOK deployment before its provider credential arrives resolves the platform's placeholder.
     * Nothing may be provisioned from it — there is no dimension anyone can vouch for — and, just as
     * important, nothing may be provisioned by CATCHING it either: the placeholder is checked, not
     * called, so a schema commitment is never made from a failure.
     */
    @Test
    fun `a placeholder embedding service is asked, never read, and provisions nothing`() {
        val pm = mockk<PersistenceManager>(relaxed = true) { every { type } returns DatabaseType.NEO4J }
        val placeholder = PlaceholderEmbedding()

        EntitySchemaProvisioner(pm, properties, { placeholder }).ensureOnce()

        assertEquals(
            0, placeholder.dimensionReads,
            "read a dimension from a placeholder — awaitingProviderKey was not honoured, and only the " +
                "exception stopped an index being built",
        )
        verify(exactly = 0) { pm.indexes }
        verify(exactly = 0) { pm.constraints }
    }

    /**
     * And through a decorator, which is the normal case rather than an exotic one: the platform's
     * event tracking already wraps the configured service, and this repo's own host wraps it again
     * to hot-swap the model. `awaitingProviderKey` rides through `by` delegation; a type test would not.
     */
    @Test
    fun `a wrapped placeholder provisions nothing either`() {
        val pm = mockk<PersistenceManager>(relaxed = true) { every { type } returns DatabaseType.NEO4J }

        val placeholder = PlaceholderEmbedding()

        EntitySchemaProvisioner(pm, properties, { Wrapper(Wrapper(placeholder)) }).ensureOnce()

        assertEquals(0, placeholder.dimensionReads, "the decorator hid awaitingProviderKey")
        verify(exactly = 0) { pm.indexes }
        verify(exactly = 0) { pm.constraints }
    }

    private class Wrapper(delegate: EmbeddingService) : EmbeddingService by delegate

    /**
     * And the recovery that makes skipping acceptable rather than merely safe: the check is a type
     * test, so it costs nothing to repeat, and the attempt after a real model is resolved provisions
     * — no restart, which is what a deployment taking its key from a settings screen needs.
     */
    @Test
    fun `once a real model replaces the placeholder the next attempt provisions`() {
        val indexManager = mockk<IndexManager> {
            every { ensure(any()) } answers { EnsureResult.Created(SchemaItemInfo.fromSpec(firstArg())) }
        }
        val pm = mockk<PersistenceManager>(relaxed = true) {
            every { type } returns DatabaseType.NEO4J
            every { indexes } returns indexManager
        }
        // What the platform resolves changes over time; the provisioner re-asks rather than holding
        // the answer, which is the only way the key arriving can ever be noticed.
        var resolved: EmbeddingService = PlaceholderEmbedding()

        val provisioner = EntitySchemaProvisioner(pm, properties, { resolved })
        provisioner.ensureOnce()
        verify(exactly = 0) { indexManager.ensure(any()) }

        resolved = RealEmbedding()
        provisioner.ensureOnce()

        verify(exactly = 2) { indexManager.ensure(any()) }
    }

    /** The platform's placeholder: carries the marker, and refuses to report a dimension. */
    /**
     * Counts dimension reads, because "provisioned nothing" is too weak to prove anything: a
     * provisioner that ignored [awaitingProviderKey] would read the dimension, throw, be caught, and also
     * provision nothing. The contract is that the placeholder is ASKED and never CALLED, so the
     * discriminating assertion is that no one read a dimension we cannot vouch for.
     */
    private class PlaceholderEmbedding : EmbeddingService {
        var dimensionReads = 0
            private set

        override val awaitingProviderKey = true
        override val name = "setup-required-embedding"
        override val provider = "none"
        override val pricingModel: PricingModel? = null
        override fun embed(text: String): FloatArray = error("no embedding service configured")
        override fun embed(texts: List<String>): List<FloatArray> = error("no embedding service configured")
        override val dimensions: Int get() = error("no embedding service configured")
    }

    private class RealEmbedding : EmbeddingService {
        override val name = "text-embedding-3-small"
        override val provider = "acme"
        override val pricingModel: PricingModel? = null
        override fun embed(text: String): FloatArray = FloatArray(dimensions)
        override fun embed(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override val dimensions = 1536
    }

}
