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
 * @param enabled false disables all schema work — for tests with no live database, and for callers
 *        that manage the entity schema themselves.
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
}
