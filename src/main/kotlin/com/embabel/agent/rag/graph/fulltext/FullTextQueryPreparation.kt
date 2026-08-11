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

private val logger = LoggerFactory.getLogger("com.embabel.agent.rag.graph.fulltext")

/**
 * Runs [search] with identifier-shaped tokens promoted to required terms, falling back to the
 * original query if that matches nothing.
 *
 * Every full-text entry point in this module routes through here — chunk search, filtered chunk
 * search, and entity search — so that the same question cannot become less precise merely because it
 * arrived by a different door or carried a metadata filter.
 *
 * The fallback is what makes this safe on by default: an identifier that is absent from the corpus,
 * or mis-typed, degrades to the previous behaviour rather than to an empty result, so recall cannot
 * regress. It costs the honest "not found" answer for an unknown identifier; pass [enabled] `false`
 * to opt out of the mechanism entirely.
 */
internal fun <T> searchRequiringIdentifiers(
    query: String,
    enabled: Boolean,
    search: (String) -> List<T>,
): List<T> {
    if (!enabled) return search(query)

    val prepared = FullTextQueryPreparation.prepare(query)
    val results = search(prepared.query)
    if (results.isNotEmpty() || prepared.fallback == null) return results

    logger.debug(
        "Required-term query '{}' matched nothing; falling back to '{}'",
        prepared.query, prepared.fallback,
    )
    return search(prepared.fallback)
}

/**
 * A full-text query rewritten so that its identifier-shaped tokens are *required*, together with
 * the original query to fall back to if the rewrite matches nothing.
 *
 * @param query the query to run first
 * @param fallback the original query, or `null` if no rewrite happened (so nothing to fall back to)
 */
internal data class PreparedFullTextQuery(
    val query: String,
    val fallback: String?,
) {
    val wasRewritten: Boolean get() = fallback != null
}

/**
 * Rewrites a full-text query so identifiers behave like identifiers.
 *
 * ## Why
 *
 * Full-text search exists for the retrieval a vector index cannot do: finding an error code, a part
 * number, a stack-trace token — strings with no useful embedding. The BM25 *score* cannot express
 * that intent, because it sums per-term contributions and so tracks query length as much as term
 * rarity. Measured on an 851-document corpus with a normalized score of `s/(s+3)`:
 *
 * | query                                          | hits | score |
 * |------------------------------------------------|------|-------|
 * | `ER20328_23` (present in exactly 1 document)    | 1    | 0.459 |
 * | `error code ER20328_23 in the payment service`  | 851  | 0.520 |
 * | `error code timeout failure` (no identifier)    | 744  | 0.434 |
 *
 * Padding the identifier query with ordinary words matched the entire corpus and *raised* the top
 * score. Noise (0.434) outscores a legitimate exact match elsewhere in the same corpus (0.357 for a
 * code appearing in 40 documents). No similarity floor separates those populations, so no threshold
 * can make full-text precise.
 *
 * The result set can. Requiring the identifier collapses that same 851-hit query to the 1 document
 * that actually contains the code, leaving the top score untouched at 3.24 raw — proof that precision
 * was always in set membership rather than in the number. Optional terms still rank *within* the
 * required set, so context words continue to do useful work.
 *
 * ## What it does
 *
 * Prefixes identifier-shaped tokens with Lucene's required-term operator `+`, leaving ordinary words
 * as optional terms. `what causes ER20328_23` becomes `+ER20328_23 what causes`.
 *
 * Two deliberate limits:
 * - A query already containing Lucene operators is passed through untouched. Someone who wrote
 *   `+foo -bar` meant it, and second-guessing them would corrupt a deliberate query.
 * - The rewrite is always paired with a [PreparedFullTextQuery.fallback], so a required term that
 *   happens to be absent (a typo'd code, an identifier not in the corpus) degrades to the original
 *   query rather than to an empty result. Recall cannot regress.
 *
 * Assumes Lucene-compatible query syntax — true for Neo4j's full-text indexes and Memgraph's
 * Tantivy-backed `text_search`, not for RediSearch-backed engines. Disable via
 * `requireIdentifierTerms` where that assumption does not hold.
 */
internal object FullTextQueryPreparation {

    /**
     * Shortest token still treated as an identifier. Below this, digit-and-letter mixes are
     * overwhelmingly ordinary words or version fragments (`v2`, `a1`) that should stay optional.
     */
    private const val MIN_IDENTIFIER_LENGTH = 4

    /**
     * Characters that only ever appear in Lucene syntax, wherever they occur. Their presence
     * suppresses the rewrite entirely.
     */
    private val ALWAYS_OPERATOR_CHARS = setOf('"', '*', '~', ':', '(', ')', '[', ']', '{', '}', '^', '\\', '?')

    /**
     * Characters that are operators only in *leading* position. `-` and `+` also occur inside
     * ordinary identifiers — `PN-88421-C` is a part number, not an exclusion — so position, not
     * presence, is what distinguishes a hand-written query from a typed one.
     */
    private val PREFIX_OPERATOR_CHARS = setOf('+', '-', '!')

    /** Bare boolean operators, which likewise indicate a hand-written query. */
    private val BOOLEAN_KEYWORDS = setOf("AND", "OR", "NOT", "TO")

    /**
     * Sentence punctuation stripped from the end of the whole query before anything else. A user
     * asking "what causes ER20328_23?" is not writing a single-character wildcard.
     */
    private const val SENTENCE_PUNCTUATION = "?!."

    /**
     * Characters a standard analyzer breaks a token on. An identifier containing one of these is
     * quoted so it becomes a phrase query over its parts rather than several loose terms.
     * `_` is absent on purpose: Unicode word-break rules join across it, so `ER20328_23` survives
     * tokenization whole.
     */
    private val TOKENIZER_SPLIT_CHARS = setOf('-', '.', '/', '\\', ',')

    /** Punctuation stripped from a token's edges before it is classified or emitted. */
    private const val TRIMMABLE = ".,;:!?'\"()[]{}"

    /**
     * Rewrite [raw] so identifier-shaped tokens are required, or return it unchanged when there is
     * nothing to gain (blank, hand-written Lucene syntax, or no identifiers present).
     */
    fun prepare(raw: String): PreparedFullTextQuery {
        val trimmed = raw.trim().trimEnd(*SENTENCE_PUNCTUATION.toCharArray()).trim()
        if (trimmed.isEmpty() || containsLuceneSyntax(trimmed)) {
            return PreparedFullTextQuery(query = trimmed, fallback = null)
        }
        val tokens = trimmed.split(WHITESPACE).filter { it.isNotBlank() }
        val identifiers = tokens.filter { isIdentifierLike(it.trim(*TRIMMABLE.toCharArray())) }
        if (identifiers.isEmpty()) {
            return PreparedFullTextQuery(query = trimmed, fallback = null)
        }
        // Required terms lead so the rewritten query reads the way it behaves: the identifiers
        // decide membership, everything else only ranks within that set.
        val required = identifiers.map { requireTerm(it.trim(*TRIMMABLE.toCharArray())) }
        val optional = tokens.filterNot { isIdentifierLike(it.trim(*TRIMMABLE.toCharArray())) }
        return PreparedFullTextQuery(
            query = (required + optional).joinToString(" "),
            fallback = trimmed,
        )
    }

    private val WHITESPACE = Regex("\\s+")

    private fun containsLuceneSyntax(query: String): Boolean {
        val tokens = query.split(WHITESPACE)
        return query.any { it in ALWAYS_OPERATOR_CHARS } ||
            tokens.any { it.length > 1 && it.first() in PREFIX_OPERATOR_CHARS } ||
            tokens.any { it in BOOLEAN_KEYWORDS }
    }

    /**
     * An identifier is a token mixing letters and digits — an error code, part number, SKU, hash.
     * Pure words carry no digits; pure numbers (years, quantities) carry no letters and are far too
     * common to force into a result set.
     */
    private fun isIdentifierLike(token: String): Boolean =
        token.length >= MIN_IDENTIFIER_LENGTH &&
            token.any { it.isDigit() } &&
            token.any { it.isLetter() }

    private fun requireTerm(token: String): String =
        if (token.any { it in TOKENIZER_SPLIT_CHARS }) "+\"$token\"" else "+$token"
}
