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
 * How this store reads the `query` string it is given.
 *
 * The two are exclusive on purpose. Escaping *part* of the input is predictable to nobody, human or
 * model: the same `+` would be an operator in one query and a literal in the next. So a query is
 * either wholly the caller's expression or wholly literal text, and [FullTextQueryPreparation]
 * enforces that split.
 *
 * Mirrors `TextQueryMode` in `embabel-agent-rag-core` (embabel/embabel-agent#1916). Kept local for
 * now so this module does not require the published artifact to carry that type yet — the same
 * reasoning that keeps `bm25K` local. Delete this and override `supportedQueryModes` /
 * `queryMode` once the artifact ships it.
 */
enum class FullTextQueryMode {

    /**
     * The caller's query is **literal text**. It is escaped in full — no character can be mistaken
     * for an operator, so a pasted URL, file path or code fragment is searched for rather than
     * rejected by the query parser.
     *
     * Precision then has to come from this store rather than the caller, so identifier-shaped
     * tokens are promoted to required terms on the caller's behalf. Choose this for callers that
     * cannot reliably compose an expression: a small model is likelier to emit a malformed query
     * than a useful one, and here it cannot emit one at all.
     */
    LITERAL,

    /**
     * The caller's query is a **Lucene expression**, passed through untouched. The caller owns
     * precision — and owns escaping, so an unbalanced `/` is a parse failure rather than a search.
     *
     * The default, because it is what this store has always done, and because a capable model does
     * comply when the tool description asks it to: measured 3/3 on gpt-4.1-mini with instructions
     * to require identifiers, against 0/3 with the notes that shipped before.
     */
    EXPRESSION,
}
