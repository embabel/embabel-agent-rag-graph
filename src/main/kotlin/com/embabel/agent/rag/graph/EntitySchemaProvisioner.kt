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

import com.embabel.common.ai.model.EmbeddingService
import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ensures the two entity indexes [DrivineNamedEntityDataRepository] binds **by name** —
 * `properties.entityIndex` into `db.index.vector.queryNodes`, `properties.entityFullTextIndex` into
 * the text search. The repository is the component that requires them, so it is the component that
 * creates them: [DrivineStore] declares the same pair, but an application whose primary store is
 * [GraphObjectManagerStore] never constructs one (that store models no entities, so it provisions no
 * entity indexes — see [GraphProvisioner]) and would leave every entity search failing against an
 * index nobody created.
 *
 * ## Why this is a collaborator and not an `init` block
 *
 * Building the vector spec reads [EmbeddingService.dimensions], which interrogates a live embedding
 * model. In a **BYOK deployment there is no model at boot**: the provider credential is supplied by
 * the operator at first-run setup, after the application is already up and serving the screens they
 * type it into. A first boot therefore runs this cold *by design*, not by accident — so the attempt
 * cannot be fatal, and, just as importantly, it cannot be the only attempt. A one-shot constructor
 * ensure leaves entity search broken for the life of the process even after the key arrives.
 *
 * So the attempt is made at construction (where it succeeds on any already-configured deployment,
 * keeping boot-time provisioning) and **retried at the point of use** until one succeeds. The point
 * of use is also the first moment the dimension is meaningful.
 *
 * The repository is a `data class` whose narrowed views are `copy()`s; they carry this collaborator
 * with them, so [ensureOnce] is settled once per root repository rather than once per view.
 *
 * ## Not-yet versus never
 *
 * The retry exists for conditions that can resolve: no embedding model *yet*, a driver that cannot
 * answer *yet*. An engine with no schema management at all (Neptune, Postgres — Drivine's
 * `UnsupportedSchemaGrammar` throws on every call, deliberately) resolves never, and retrying it
 * would spend a failed round-trip on every search for the life of the process. That is a
 * configuration error, so it fails here, at construction: this repository binds its indexes by name
 * and cannot work on an engine that cannot have them.
 *
 * @param enabled false disables all schema work, including the engine check — for tests with no live
 *        database, and for callers that manage the entity schema themselves.
 */
class EntitySchemaProvisioner(
    private val persistenceManager: PersistenceManager,
    private val properties: GraphRagServiceProperties,
    private val embeddingService: EmbeddingService,
    private val enabled: Boolean = true,
) {

    private val logger = LoggerFactory.getLogger(EntitySchemaProvisioner::class.java)

    private val provisioner = GraphProvisioner(persistenceManager)

    private val ensured = AtomicBoolean(false)

    init {
        if (enabled && persistenceManager.type !in SCHEMA_CAPABLE) {
            throw DrivineException(
                "${persistenceManager.type} has no schema management, so the entity indexes " +
                    "'${properties.entityIndex}' and '${properties.entityFullTextIndex}' that entity " +
                    "search binds by name cannot be created. Supported engines: " +
                    SCHEMA_CAPABLE.joinToString { it.value } +
                    ". Pass verifyIndexes = false if you provision the entity schema yourself."
            )
        }
    }

    /**
     * Create the entity indexes if they are absent, idempotently — [GraphProvisioner.ensureSchema]
     * matches an existing index by `(label, properties)`, so a database already carrying them is left
     * alone. Cheap to call on every search: once an attempt succeeds this returns on an atomic read.
     *
     * Never fatal, and never final. An absent embedding model (BYOK, pre-setup), a read-only
     * database, a driver that cannot answer yet, or a user without schema privileges all leave the
     * application able to serve everything that is not entity search — and all of them can resolve
     * later, which is why a failed attempt leaves the flag unset and is tried again.
     */
    fun ensureOnce() {
        if (!enabled || ensured.get()) return
        try {
            provisioner.ensureSchema(
                vectorIndexes = listOf(
                    VectorIndexSpec(
                        properties.entityNodeName, "embedding", embeddingService.dimensions,
                        SimilarityFunction.COSINE, properties.entityIndex,
                    ),
                ),
                fullTextIndexes = listOf(
                    FullTextIndexSpec(
                        properties.entityNodeName, listOf("name", "description"),
                        properties.entityFullTextIndex,
                    ),
                ),
                constraints = emptyList(),
            )
            ensured.set(true)
        } catch (e: Exception) {
            logger.warn(
                "Could not ensure entity indexes {} and {}: {}. Entity search will fail until they " +
                    "exist; retrying on the next search.",
                properties.entityIndex, properties.entityFullTextIndex, e.message,
            )
        }
    }

    companion object {
        /** Engines whose grammar can create the indexes entity search binds by name. */
        private val SCHEMA_CAPABLE = setOf(DatabaseType.NEO4J, DatabaseType.MEMGRAPH, DatabaseType.FALKORDB)
    }
}
