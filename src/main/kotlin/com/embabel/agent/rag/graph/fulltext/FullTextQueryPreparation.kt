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

import org.slf4j.LoggerFactory

/**
 * A query ready for the index, plus the query to fall back to if it matches nothing.
 *
 * @param query the query to run first
 * @param fallback a less restrictive form, or `null` when there is nothing to fall back to
 */
internal data class PreparedFullTextQuery(
    val query: String,
    val fallback: String?,
) {
    /**
     * Whether any term was REQUIRED, as opposed to merely escaped.
     *
     * Equivalent to carrying a fallback, and that is not a coincidence: requiring a term is the only
     * thing that can narrow a result set to nothing, so it is the only thing worth retreating from.
     */
    val requiresTerms: Boolean get() = fallback != null
}

/**
 * The single point at which this store escapes input or injects operators.
 *
 * Everything hinges on [FullTextQueryMode]:
 *
 *  - [FullTextQueryMode.EXPRESSION] — the caller wrote a Lucene expression. Passed through byte for
 *    byte. Nothing is escaped and nothing is added, so what the caller wrote is what runs.
 *  - [FullTextQueryMode.LITERAL] — the caller wrote words. Every token is escaped, then the terms an
 *    extractor identifies are required. The caller cannot express an operator, and equally cannot
 *    trip over one.
 *
 * There is deliberately no middle. Escaping some characters and not others produces a surface where
 * `+` is an operator in one query and a literal in the next, which no caller can reason about.
 *
 * ## Why LITERAL requires anything at all
 *
 * BM25 sums per-term contributions, so its score tracks query length as much as term rarity.
 * Measured on an 851-document corpus, `error code ER20328_23 in the payment service` matched all
 * 851 documents and scored 0.520, while prose containing no identifier scored 0.434 and forty
 * *exact* matches on one identifier scored between 0.289 and 0.357. No similarity floor separates
 * those populations. Requiring the identifier collapses that same query to the single document that
 * contains the code, with the top raw score unchanged — precision lives in the result set, not the
 * number.
 */
internal object FullTextQueryPreparation {

    private val logger = LoggerFactory.getLogger(FullTextQueryPreparation::class.java)

    /**
     * Characters Lucene's parser treats as syntax. Escaped one for one under
     * [FullTextQueryMode.LITERAL] so none of them can reach the parser as an operator.
     *
     * `/` is the one that bites in practice: it opens a regex term, so an odd number of slashes in a
     * pasted URL is an unterminated regex and the whole search fails rather than returning nothing.
     */
    private const val LUCENE_SPECIAL = "+-&|!(){}[]^\"~*?:\\/"

    private val WHITESPACE = Regex("\\s+")

    /** Sentence punctuation stripped from the end of a query — a question mark is not a wildcard. */
    private const val SENTENCE_PUNCTUATION = "?!."

    fun prepare(
        rawQuery: String,
        mode: FullTextQueryMode,
        extractor: RequiredTermExtractor,
    ): PreparedFullTextQuery = when (mode) {
        FullTextQueryMode.EXPRESSION -> PreparedFullTextQuery(query = rawQuery, fallback = null)
        FullTextQueryMode.LITERAL -> literal(rawQuery, extractor)
    }

    private fun literal(rawQuery: String, extractor: RequiredTermExtractor): PreparedFullTextQuery {
        val trimmed = rawQuery.trim().trimEnd(*SENTENCE_PUNCTUATION.toCharArray()).trim()
        if (trimmed.isEmpty()) return PreparedFullTextQuery(query = "", fallback = null)

        val tokens = trimmed.split(WHITESPACE).filter { it.isNotBlank() }
        val escaped = tokens.map(::escape)
        val plain = escaped.joinToString(" ")

        val required = extractor.requiredTerms(trimmed).map(::escape).toSet()
        if (required.isEmpty()) return PreparedFullTextQuery(query = plain, fallback = null)

        // Required terms lead so the query reads the way it behaves: they decide membership, the
        // rest only ranks within it.
        val ordered = escaped.filter { it in required }.map { "+$it" } + escaped.filterNot { it in required }
        return PreparedFullTextQuery(
            query = ordered.joinToString(" "),
            // Falling back to the escaped-but-unrequired form means a mis-identified or absent
            // identifier costs a second query rather than every result. Recall cannot regress
            // relative to searching the same words with nothing required.
            fallback = plain,
        )
    }

    /**
     * Escapes [token] so Lucene's parser reads every character literally.
     *
     * Note what this does NOT do: escaping is a parser concern, so an escaped `PN-88421-C` reaches
     * the analyzer whole and is still split into `pn`, `88421`, `c` there — which is the behaviour
     * wanted, since the parts then form a phrase rather than three loose terms.
     */
    private fun escape(token: String): String = buildString(token.length * 2) {
        token.forEach { c ->
            if (c in LUCENE_SPECIAL) append('\\')
            append(c)
        }
    }
}

/**
 * Runs [search] with [query] prepared for [mode], falling back once if a required-term form matches
 * nothing.
 *
 * Every full-text entry point in this module routes through here — chunk search, filtered chunk
 * search and entity search — so the same question cannot become less precise merely because it
 * arrived by a different door or carried a metadata filter.
 */
internal fun <T> searchPreparedQuery(
    query: String,
    mode: FullTextQueryMode,
    extractor: RequiredTermExtractor,
    search: (String) -> List<T>,
): List<T> {
    val prepared = FullTextQueryPreparation.prepare(query, mode, extractor)
    val results = search(prepared.query)
    if (results.isNotEmpty() || prepared.fallback == null || prepared.fallback == prepared.query) {
        return results
    }
    LoggerFactory.getLogger(FullTextQueryPreparation::class.java).debug(
        "Required-term query '{}' matched nothing; falling back to '{}'", prepared.query, prepared.fallback,
    )
    return search(prepared.fallback)
}
