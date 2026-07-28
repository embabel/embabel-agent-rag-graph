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

import org.drivine.manager.PersistenceManager
import org.drivine.schema.EnsureResult
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.slf4j.LoggerFactory

/**
 * Graph-structure provisioning shared by [DrivineStore] and [GraphObjectManagerStore] — the mechanics
 * they had duplicated: applying a schema through Drivine's index/constraint managers, and creating the
 * `HAS_PARENT` hierarchy edges.
 *
 * A collaborator, not a base class: each store *owns* its schema (the spec lists differ — the GOM store
 * models no entities, so it provisions no entity indexes) and its chunk-sequence edges (per-`parentId`
 * `PART_OF`/`FIRST_CHUNK`/`NEXT_CHUNK` vs. a batch `NEXT_CHUNK`); only the common mechanics live here.
 */
class GraphProvisioner(
    private val persistenceManager: PersistenceManager,
) {

    private val logger = LoggerFactory.getLogger(GraphProvisioner::class.java)

    /**
     * Ensure the given schema, applied per-spec (idempotently) through Drivine's
     * [org.drivine.schema.IndexManager] / [org.drivine.schema.ConstraintManager], so a store only ever
     * touches its own indexes and never collides with schema a host application manages on the same
     * database.
     *
     * Drivine's `ensure` matches an index by `(label, properties)` and **ignores the name** — by design,
     * it reports and the caller decides. So an index created by the *other* store (e.g. [DrivineStore]
     * under a different name) satisfies `ensure` and is left in place — but `gom.loadNearest` /
     * `loadMatching` resolve by the *convention* name, so search would fail. Our decision:
     * [ensureUnderConventionName] recreates such an index under the spec's convention name. This makes a
     * store switch a self-healing rebuild (node data and embeddings survive), and once the name matches,
     * runs in that direction are a no-op.
     *
     * It is one-time **per direction**, not per A/B cycle. Where the two stores resolve *different* names
     * for the same index — the chunk vector index ([GraphObjectManagerStore] uses the convention name,
     * [DrivineStore] its configured `contentElementIndex`) — each flip rebuilds it, with degraded vector
     * recall until it completes. The chunk full-text index name *is* aligned across both stores, so it is
     * not rebuilt on a flip; aligning the vector index name too would make the A/B switch seamless.
     */
    fun ensureSchema(
        vectorIndexes: List<VectorIndexSpec>,
        fullTextIndexes: List<FullTextIndexSpec>,
        constraints: List<UniquenessConstraintSpec>,
    ) {
        (vectorIndexes + fullTextIndexes).forEach { ensureUnderConventionName(it) }
        constraints.forEach { persistenceManager.constraints.ensure(it) }
    }

    private fun ensureUnderConventionName(spec: IndexSpec) {
        when (val result = persistenceManager.indexes.ensure(spec)) {
            is EnsureResult.AlreadyMatching ->
                if (result.info.name != spec.effectiveName) {
                    logger.info(
                        "Index on {}{} exists as '{}' but this store resolves '{}' — recreating under the convention name",
                        spec.label, spec.properties, result.info.name, spec.effectiveName,
                    )
                    persistenceManager.indexes.recreate(spec)
                }

            is EnsureResult.Drift -> logger.warn(
                "Index shape drift on {}{}: existing {} vs requested {}; leaving in place (recreate() would rebuild, destructive)",
                spec.label, spec.properties, result.existing, spec,
            )

            else -> {} // Created / Recreated — nothing to decide
        }
    }

    /**
     * Transitional: WARN if any chunk carries free-form metadata as flat properties (the legacy
     * [DrivineStore] layout) rather than in the `metadata.` bag [GraphObjectManagerStore] filters on.
     * Delegates to [LegacyChunkMetadataCheck] — remove both once installs have migrated.
     */
    fun warnOnLegacyChunkMetadata(chunkLabel: String, knownFlatProperties: Set<String>) =
        LegacyChunkMetadataCheck.warnIfLegacyFlatMetadata(persistenceManager, chunkLabel, knownFlatProperties)

    /**
     * Create the `HAS_PARENT` edges from each content element's `parentId` — the batch traversal both
     * stores run to build the hierarchy the recursive content-tree views walk.
     */
    fun createHasParentEdges() {
        persistenceManager.executeCypher(
            purpose = "Create HAS_PARENT relationships",
            cypher = """
                MATCH (child:ContentElement) WHERE child.parentId IS NOT NULL
                WITH child
                MATCH (parent:ContentElement {id: child.parentId})
                MERGE (child)-[:HAS_PARENT]->(parent)
            """.trimIndent(),
        )
    }
}
