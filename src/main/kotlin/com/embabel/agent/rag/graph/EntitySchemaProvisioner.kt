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
import org.drivine.manager.PersistenceManager
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Ensures the two entity indexes [DrivineNamedEntityDataRepository] binds **by name** —
 * `properties.entityIndex` into `db.index.vector.queryNodes`, `properties.entityFullTextIndex` into
 * the text search. The repository is the component that requires them, so it is the component that
 * creates them: [DrivineStore] declares the same pair, but an application whose primary store is
 * [GraphObjectManagerStore] never constructs one (that store models no entities, so it provisions no
 * entity indexes — see [GraphProvisioner]) and would leave every entity search failing against an
 * index nobody created.
 *
 * ## Never provision from a dimension we cannot vouch for
 *
 * Building the vector spec reads [EmbeddingService.dimensions], and that number is a schema
 * commitment: it becomes the shape of the index, writes to it succeed, and a model configured later
 * that disagrees surfaces as empty search results rather than as an error. [GraphProvisioner]
 * reports that later mismatch as `EnsureResult.Drift`, which it logs and leaves in place — so a
 * wrong dimension survives every boot behind a warning nobody reads.
 *
 * So the question asked here is [EmbeddingService.awaitingProviderKey] — whether there is a model yet — not
 * whether reading the dimension happens to throw. A failure cannot be told apart from a provider
 * that is merely unreachable right now, and a placeholder that answered with a plausible number
 * would not fail at all. The property rather than a type test, because the platform's own event
 * tracking already decorates the configured service and a wrapper around a placeholder is not
 * itself one.
 *
 * ## When it runs
 *
 * At construction, and again on each search until one attempt succeeds — the marker check is a type
 * test, so repeating it costs nothing.
 *
 * The embedding service is taken as a **supplier, not an instance**, and that is load-bearing rather
 * than stylistic. The marker lives on the object, so a held reference that is the placeholder stays
 * the placeholder forever: re-checking it can never notice the key arriving, and recovery would need
 * a restart. Re-resolving asks the platform each time, which answers the placeholder now and a real
 * service once one is registered. A host that wants entity search to start working without a restart
 * must pass a supplier that actually re-resolves — `{ modelProvider.getEmbeddingService(...) }` —
 * rather than one closing over a value it captured at construction.
 *
 * The repository is a `data class` whose narrowed views are `copy()`s; they carry this collaborator
 * with them, so [ensureOnce] is settled once per root repository rather than once per view.
 *
 * An engine with no schema management at all (Neptune, Postgres — Drivine's
 * `UnsupportedSchemaGrammar` throws on every call, deliberately) can never succeed, so it is refused
 * at construction rather than retried on every search forever. That is asked of the manager
 * (`supportsSchemaManagement`), not inferred from the engine's name: capability belongs to the
 * grammar, and an engine this class has never heard of may resolve a perfectly capable one.
 *
 * ## A model that changes
 *
 * The vector index declares a width, and a model swapped at runtime for one of another width leaves
 * an index nothing can be searched with: the engine refuses a query vector that is not the index's
 * width. So what is remembered is the WIDTH the indexes were ensured at, not merely that they were.
 * A search under a model of another width ensures again, and an index found at the old width is
 * rebuilt at the new one. Whatever vectors it held were the old model's, and are no more searchable
 * by the new model in the old index than in none.
 *
 * @param enabled false disables all schema work, including the engine check — for tests with no live
 *        database, and for callers that manage the entity schema themselves.
 */
class EntitySchemaProvisioner(
    private val persistenceManager: PersistenceManager,
    private val properties: GraphRagServiceProperties,
    private val embeddingService: () -> EmbeddingService,
    private val enabled: Boolean = true,
) {

    private val logger = LoggerFactory.getLogger(EntitySchemaProvisioner::class.java)

    private val provisioner = GraphProvisioner(persistenceManager)

    /** The embedding width the indexes were last ensured at, or null when they have not been. */
    private val ensuredAt = AtomicReference<Int?>(null)

    init {
        if (enabled && !persistenceManager.supportsSchemaManagement) {
            throw DrivineException(
                "${persistenceManager.type} has no schema management, so the entity indexes " +
                    "'${properties.entityIndex}' and '${properties.entityFullTextIndex}' that entity " +
                    "search binds by name cannot be created. " +
                    "Pass verifyIndexes = false if you provision the entity schema yourself."
            )
        }
    }

    /**
     * Create the entity indexes if they are absent, idempotently — [GraphProvisioner.ensureSchema]
     * matches an existing index by `(label, properties)`, so a database already carrying them is left
     * alone. Cheap to call on every search: once an attempt succeeds this asks the model its width,
     * compares it with the one remembered, and returns.
     *
     * Not fatal: a read-only database, a driver that cannot answer yet, or a user without schema
     * privileges all leave the application able to serve everything that is not entity search. A
     * failed attempt leaves the flag unset, so the next search tries again.
     */
    fun ensureOnce() {
        if (!enabled) return
        val embeddings = embeddingService()
        if (embeddings.awaitingProviderKey) {
            // No model yet, so no dimension anyone can vouch for. Skipping is the recoverable
            // answer: the next search re-checks, and provisions once a real model is resolved.
            logger.debug(
                "Awaiting an embedding model; entity indexes {} and {} not provisioned yet",
                properties.entityIndex, properties.entityFullTextIndex,
            )
            return
        }
        try {
            val width = embeddings.dimensions
            if (ensuredAt.get() == width) return
            provisioner.ensureSchema(
                vectorIndexes = listOf(
                    VectorIndexSpec(
                        properties.entityNodeName, "embedding", width,
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
                // The entity index follows the model: see "A model that changes" above.
                rebuildAtAnotherWidth = true,
            )
            ensuredAt.set(width)
        } catch (e: Exception) {
            logger.warn(
                "Could not ensure entity indexes {} and {}: {}. Entity search will fail until they " +
                    "exist; retrying on the next search.",
                properties.entityIndex, properties.entityFullTextIndex, e.message,
            )
        }
    }
}
