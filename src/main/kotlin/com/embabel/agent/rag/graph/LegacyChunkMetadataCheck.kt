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
import org.slf4j.LoggerFactory

/**
 * **Transitional migration aid — delete this file (and its one call in [GraphProvisioner]) once installs
 * have moved off the legacy flat-metadata layout.**
 *
 * [GraphObjectManagerStore] persists free-form chunk metadata in a `metadata.*` property bag; the legacy
 * [DrivineStore] wrote it as flat node properties. Metadata-filtered search resolves keys to the bag path,
 * so flat legacy metadata is silently unfilterable — the chunks are present and vector-searchable, but
 * invisible to any `PropertyFilter`, with no error to hint at why. This samples chunks at provision time
 * and, if it finds free-form metadata stored flat, logs a WARN naming the offending keys, so a bean swap
 * onto a populated graph fails loudly rather than as silent zero-results. It reads only (changes no data);
 * the cure is a flat→bag migration or a re-ingest.
 */
internal object LegacyChunkMetadataCheck {

    private val logger = LoggerFactory.getLogger(LegacyChunkMetadataCheck::class.java)

    /** Bounded sample — a whole-store migration shows up in the first nodes, so provision stays cheap. */
    private const val SAMPLE = 500

    /**
     * WARN if any of the first [SAMPLE] `chunkLabel` nodes carries a flat property outside
     * [knownFlatProperties] and outside the `metadata.` bag — i.e. free-form metadata in the old flat
     * layout. A flat key not in [knownFlatProperties] is, by construction, not one this store writes.
     */
    fun warnIfLegacyFlatMetadata(
        persistenceManager: PersistenceManager,
        chunkLabel: String,
        knownFlatProperties: Set<String>,
    ) {
        val rows = persistenceManager.queryForRows(
            purpose = "Detect legacy flat chunk metadata (migration warning)",
            // `$($chunkLabel)` / `$($sampleSize)` are Drivine `render` inlines (label + numeric limit can't
            // be bound params); `$known` is a bound parameter. ($$ string: a single `$` is literal Cypher.)
            // One distinct stray key per row (a scalar column, so it maps cleanly) — collected below; no
            // stray keys → no rows → no warning.
            cypher = $$"""
                MATCH (c:$($chunkLabel))
                WITH c LIMIT $($sampleSize)
                UNWIND [k IN keys(c) WHERE NOT k STARTS WITH 'metadata.' AND NOT k IN $known] AS strayKey
                RETURN DISTINCT strayKey AS strayKey
            """.trimIndent(),
            params = mapOf("known" to knownFlatProperties.toList()),
            render = mapOf("chunkLabel" to chunkLabel, "sampleSize" to SAMPLE.toString()),
        )

        val strayKeys = rows.mapNotNull { it["strayKey"] as? String }
        if (strayKeys.isNotEmpty()) {
            logger.warn(
                "Found chunks with free-form metadata stored as FLAT properties {} instead of in the " +
                    "'metadata.'-prefixed bag this store filters on (sampled up to {} '{}' nodes). Such " +
                    "chunks are vector-searchable but INVISIBLE to metadata-filtered search — they were " +
                    "written by the legacy DrivineStore. Migrate the flat keys into the metadata bag, or " +
                    "re-ingest, to make them filterable.",
                strayKeys, SAMPLE, chunkLabel,
            )
        }
    }
}
