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
 * ## When it runs
 *
 * At construction, and again on each search until one attempt succeeds. Building the vector spec
 * reads [EmbeddingService.dimensions], which interrogates a live embedding model, so a deployment
 * whose provider credential has not arrived yet cannot provision at boot; making construction the
 * only attempt would leave entity search broken for the life of the process.
 *
 * The repository is a `data class` whose narrowed views are `copy()`s; they carry this collaborator
 * with them, so [ensureOnce] is settled once per root repository rather than once per view.
 *
 * An engine with no schema management at all (Neptune, Postgres — Drivine's
 * `UnsupportedSchemaGrammar` throws on every call, deliberately) can never succeed, so it is refused
 * at construction rather than retried on every search forever.
 *
 * ## KNOWN LIMITATION — a wrong dimension is worse than no dimension
 *
 * Retrying assumes an unconfigured embedding model *fails*. If it instead returns a placeholder
 * dimension, the first attempt succeeds with the wrong number, settles, and is never revisited — and
 * [GraphProvisioner] reports the later mismatch as `EnsureResult.Drift`, which it logs and leaves in
 * place. The index then survives every boot at the wrong dimension, behind a warning, with vector
 * search quietly returning nothing.
 *
 * Provisioning must not run from a dimension it cannot vouch for. The fix is an explicit
 * provisioning call made once the embedding configuration is known to be real, and a schema version
 * keyed to the embedding model's identity so a change rebuilds the index instead of drifting
 * (Drivine's `SchemaCatalog.withVersion`). Until that lands, this is a hazard on any deployment
 * whose unconfigured embedding service answers rather than throws.
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
     * Not fatal: a read-only database, a driver that cannot answer yet, or a user without schema
     * privileges all leave the application able to serve everything that is not entity search. A
     * failed attempt leaves the flag unset, so the next search tries again — see the limitation on
     * the class: this is only sound while an unusable embedding model *fails* rather than answering
     * with a placeholder dimension.
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
