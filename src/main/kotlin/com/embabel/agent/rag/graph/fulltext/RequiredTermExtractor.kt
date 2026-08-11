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
 * Picks the terms in a query that must be MATCHED rather than merely ranked.
 *
 * Only consulted under [FullTextQueryMode.LITERAL], where the caller cannot say so themselves.
 *
 * This exists as an interface because the judgement is genuinely open. The shipped implementation
 * is lexical and therefore cheap, deterministic and portable — and demonstrably partial: measured
 * against a real 847-chunk corpus it fires on 6.4% of vocabulary, of which 86% is genuinely rare,
 * but it catches only ~8% of the tokens unique to a single chunk. It will find an error code, a
 * part number, a class name or an acronym; it will miss a rare surname or an ordinary-looking
 * technical word.
 *
 * Two alternatives were measured and rejected as defaults, and both remain viable implementations
 * here:
 *  - **Document frequency.** Language-agnostic and principled, but rarity is not intent: on that
 *    corpus `maintainability` (2 chunks) outranks `neo4j` (9 chunks), and requiring the first is
 *    wrong while requiring the second is right. It also needs a per-dialect frequency query.
 *  - **A model.** It makes exactly that distinction correctly, at the cost of a call on the search
 *    path and of determinism. Best supplied by the application, which already has the plumbing;
 *    this module deliberately has none.
 */
fun interface RequiredTermExtractor {

    /** The subset of [query]'s terms to require. Empty means "rank everything, filter nothing". */
    fun requiredTerms(query: String): List<String>

    companion object {

        /** Requires nothing — every term merely ranks. */
        val NONE: RequiredTermExtractor = RequiredTermExtractor { emptyList() }
    }
}

/**
 * A single reason a token might name something exactly rather than describe it.
 *
 * Rules are pure token shape, never language: capitalisation and morphology were both measured and
 * dropped. Capitalisation fires on **29.8%** of vocabulary — a third of every query required — and
 * it means nothing in Chinese, Japanese, Arabic or Thai and the opposite of what you want in German,
 * where every noun carries it. English suffix rules removed 11 tokens from the whole corpus, which
 * does not pay for a language assumption.
 */
fun interface TokenRule {

    fun namesSomething(token: String): Boolean
}

/**
 * The default stack: any rule may require a token, and all of them are form-based.
 *
 * Measured together on a real corpus they fire on 6.4% of vocabulary with 86% of that genuinely
 * rare — roughly triple the coverage of the digit rule alone, at similar precision.
 */
class CompositeRequiredTermExtractor(
    private val rules: List<TokenRule> = DEFAULT_RULES,
) : RequiredTermExtractor {

    override fun requiredTerms(query: String): List<String> =
        query.split(WHITESPACE)
            .map { it.trim(*TRIMMABLE) }
            .filter { it.isNotEmpty() && isAsciiIdentifierShaped(it) && rules.any { rule -> rule.namesSomething(it) } }
            .distinct()

    companion object {

        /**
         * Mixes ASCII letters and digits: error codes, part numbers, versions, short hashes.
         * `ER20328_23`, `HTTP503`, `a1b2c3d`.
         */
        val DIGIT_LETTER_MIX = TokenRule { token ->
            token.length >= 4 && token.any { it in '0'..'9' } && token.any { it.isAsciiLetter() }
        }

        /** `camelCase` or `PascalCase` — class, method and config names. `HttpURLConnection`. */
        val CAMEL_CASE = TokenRule { token ->
            token.zipWithNext().any { (a, b) -> a in 'a'..'z' && b in 'A'..'Z' }
        }

        /** A separator between alphanumerics: paths, namespaces, snake_case. `owner/repo`, `a_b`. */
        val INTERIOR_SEPARATOR = TokenRule { token ->
            SEPARATED.containsMatchIn(token)
        }

        /**
         * An all-caps run: acronyms. `GDPR`, `SOC`. Three characters minimum, so `IT` and `AS` stay
         * prose.
         *
         * [SHOUTED_FUNCTION_WORDS] are excluded because a user writing `ER20328_23 AND payment` means
         * the operator, and under LITERAL there is no operator to mean — requiring the literal word
         * "and" filters to documents that happen to contain it, which is most of them.
         *
         * Note the asymmetry with the capitalisation rule this stack deliberately omits: an
         * English-only list that only ever SUPPRESSES a rule cannot misfire in another language, it
         * simply stops applying. An English-only list that ACTIVATES one fires on 29.8% of German
         * vocabulary.
         */
        val ALL_CAPS = TokenRule { token ->
            token.length >= 3 && token.all { it in 'A'..'Z' } && token !in SHOUTED_FUNCTION_WORDS
        }

        private val SHOUTED_FUNCTION_WORDS = setOf("AND", "OR", "NOT", "THE", "FOR", "BUT", "ALL", "ANY")

        val DEFAULT_RULES: List<TokenRule> = listOf(
            DIGIT_LETTER_MIX, CAMEL_CASE, INTERIOR_SEPARATOR, ALL_CAPS,
        )

        private val WHITESPACE = Regex("\\s+")
        private val SEPARATED = Regex("[A-Za-z0-9](?:_|::|/)[A-Za-z0-9]")
        private val ASCII_SHAPED = Regex("^[A-Za-z0-9_./:-]+$")
        private val TRIMMABLE = ".,;:!?'\"()[]{}".toCharArray()

        /**
         * Restricts every rule to ASCII-shaped tokens.
         *
         * An identifier is ASCII even inside a Chinese or German document — that is what makes it
         * un-embeddable in the first place. Without this guard a script that does not delimit words
         * with spaces arrives as one enormous "token" that satisfies [DIGIT_LETTER_MIX] (CJK
         * characters are letters), and requiring that blob matches nothing.
         */
        private fun isAsciiIdentifierShaped(token: String): Boolean = ASCII_SHAPED.matches(token)

        private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    }
}
