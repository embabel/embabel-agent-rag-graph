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
 * What the model is told about query syntax — derived from [FullTextQueryMode] rather than written
 * down beside it, so the two cannot drift.
 *
 * This text reaches the LLM verbatim: `TextSearchTools` composes its tool description as
 * `"Perform BM25 text search…"` + `"Query syntax: "` + these notes, and the `query` parameter
 * description the same way. A store advertising expression syntax while escaping its input teaches
 * the model to write queries that cannot work, which is why the string is computed from the mode
 * that is actually in effect.
 *
 * The previous value was `"Full support"` — true, and useless: it names a capability without saying
 * what to type. Measured against it, a model asked about an error code emitted `+` on 0 of 3 runs,
 * and one of those runs produced the pathological form that matches an entire corpus.
 */
internal fun syntaxNotesFor(mode: FullTextQueryMode): String = when (mode) {

    // Three lessons are baked in, each easy to undo by accident:
    //  - The ABSENT case has to lead. An earlier wording said "typically one + per query" and "do not
    //    put + on ordinary words", and a model asked "how long do they have to decide our
    //    registration application?" still emitted `+registration +application +decide +how +long` —
    //    zero results, where the same words unrequired put the target at rank 1. Stating what to do
    //    when there is nothing to require is what that wording lacked.
    //  - Leaning harder on the prohibition alone made a model drop the + even where it belonged.
    //    Both examples are present so neither instinct dominates.
    //  - The worked examples did what rule-tightening could not. They use identifiers and context
    //    words found in no test, so passing shows generalisation rather than fixture-matching.
    FullTextQueryMode.EXPRESSION -> """
        Full Lucene syntax. `+term` REQUIRES a term — a document without it cannot match; unprefixed
        terms do not filter, they only rank.
        MOST QUESTIONS NEED NO + AT ALL. Use + only for an exact string an embedding cannot
        represent: an error code, part number, ticket reference, stack-trace token. If the question
        contains no such string, send the words plain.
        NEVER put + on an ordinary word, and never on more than one term unless the question really
        does contain two identifiers. Requiring several ordinary words demands they all appear in the
        same chunk, which usually matches NOTHING and loses the answer outright — far worse than
        ranking poorly.
        Example: "how long do they have to decide our registration application?"
          -> `registration application decide time limit`  (no identifier in the question, so no +)
        Example: "why did order PN-4471-B fail at checkout?"
          -> `+PN-4471-B order fail checkout`  (one identifier required; the rest only rank)
    """.trimIndent()

    // No operators are offered because none would work: under LITERAL every character is escaped.
    // Saying so plainly is the point — a model told it has syntax will use it, and here that would
    // silently search for the punctuation.
    FullTextQueryMode.LITERAL -> """
        Plain text — type the user's question as they said it. Punctuation, URLs, file paths and code
        fragments are all safe: they are searched for, not interpreted, so nothing you write can fail
        to parse. Query operators are NOT available and will be matched literally if typed.
        Identifiers in the question — error codes, part numbers, ticket references — are matched
        exactly on your behalf, so you do not need to mark them.
    """.trimIndent()
}
