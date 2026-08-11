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

    // Wording measured at 3/3 on gpt-4.1-mini, arrived at through three iterations. Two lessons are
    // baked in and easy to undo by accident:
    //  - Leaning harder on the prohibition ("NEVER prefix an ordinary word") made the model drop the
    //    + altogether on one run. The positive imperative has to stay at least as strong.
    //  - The worked example did what two rounds of rule-tightening could not. It deliberately uses a
    //    different identifier and different context words from any test, so passing demonstrates
    //    generalisation rather than matching a fixture.
    FullTextQueryMode.EXPRESSION -> """
        Full Lucene syntax. `+term` REQUIRES a term — a document without it cannot match; unprefixed
        terms do not filter, they only rank. ALWAYS put + on the identifier in the question: an error
        code, part number, ticket reference, stack-trace token, or other exact string an embedding
        cannot represent. That is what makes the search precise — without it the ordinary words match
        everything and bury the one document that carries the identifier. Do NOT put + on ordinary
        words: `+payment` discards every document that words things differently, turning a precise
        search into an empty one. Typically one + per query — the identifier required, everything
        else bare.
        Example: "why did order PN-4471-B fail at checkout?" -> `+PN-4471-B order fail checkout`
        (the part number is required; "order", "fail" and "checkout" stay bare so they only rank).
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
