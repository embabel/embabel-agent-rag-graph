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
package com.embabel.agent.rag.graph.fulltext

/**
 * Similarity floor applied to full-text results on the `RagRequest` path, in place of the caller's
 * `similarityThreshold`.
 *
 * A `RagRequest` carries **one** threshold and feeds **two** scoring systems. That number is
 * calibrated for cosine and cannot be reused for BM25, and the gap is not a matter of tuning:
 *
 * | | usable floor | why |
 * |---|---|---|
 * | cosine | ~0.8 | on a real 847-chunk corpus (1536-dim embeddings), *unrelated* chunk pairs have a median similarity of 0.712 and only 4.7% exceed 0.8 — so `RagRequest`'s 0.8 default is a defensible high-relevance cut, and vector search keeps it |
 * | BM25 | 0.0 | on a controlled 851-document corpus, forty *exact* matches on one identifier scored 0.289–0.357, and the best match for a token unique to a single chunk in the real corpus reached only 0.551. Applying 0.8 returns nothing, whatever the query |
 *
 * Zero rather than some smaller positive number, because **no** floor discriminates on this scale:
 * prose containing no identifier at all scored 0.434 in that corpus — above every legitimate exact
 * match cited above. A threshold here can only cost true positives; it cannot exclude false ones.
 * Rank and `topK` select, and precision comes from requiring the identifier
 * ([searchRequiringIdentifiers]) rather than from the score.
 *
 * Mirrors `SearchDefaults.DEFAULT_TEXT_SIMILARITY_THRESHOLD` in rag-core, which the agentic tool
 * surface already applies for the same reason. Kept local so this module does not depend on the
 * published agent artifact carrying that constant — the same reasoning that keeps `bm25K` local.
 */
internal const val FULL_TEXT_SIMILARITY_FLOOR: Double = 0.0
