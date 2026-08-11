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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the query rewrite. The end-to-end proof that it changes retrieval lives in
 * [com.embabel.agent.rag.graph.Neo4jPrecisionRetrievalTest] against a real index; these pin the
 * rewrite rules themselves, including the cases where it must NOT fire.
 */
class FullTextQueryPreparationTest {

    /** Every case here is about LITERAL, where escaping and required terms apply. */
    private fun prepareLiteral(query: String) = FullTextQueryPreparation.prepare(
        query, FullTextQueryMode.LITERAL, CompositeRequiredTermExtractor(),
    )


    @Nested
    @DisplayName("identifiers become required terms")
    inner class Identifiers {

        @Test
        fun `a bare error code is required`() {
            val prepared = prepareLiteral("ER20328_23")
            assertEquals("+ER20328_23", prepared.query)
        }

        @Test
        fun `an error code inside a sentence is required and the sentence still ranks`() {
            val prepared = prepareLiteral("what causes ER20328_23")
            assertEquals("+ER20328_23 what causes", prepared.query)
            assertTrue(prepared.requiresTerms)
        }

        @Test
        fun `trailing punctuation is not treated as a Lucene wildcard`() {
            // "ER20328_23?" would otherwise reach Lucene with a single-character wildcard attached,
            // which silently changes what matches.
            val prepared = prepareLiteral("what causes ER20328_23?")
            assertEquals("+ER20328_23 what causes", prepared.query)
        }

        @Test
        fun `a hyphenated part number is escaped so it stays one term`() {
            // Escaping is a PARSER concern: `PN\-88421\-C` reaches the analyzer whole, which then
            // splits it into pn/88421/c and forms a phrase. Quoting would achieve the same thing for
            // this token, but escaping covers every special character rather than just the splitting
            // ones, so LITERAL needs no separate quoting rule.
            val prepared = prepareLiteral("order PN-88421-C")
            assertEquals("+PN\\-88421\\-C order", prepared.query)
        }

        @Test
        fun `underscores are not quoted because the analyzer joins across them`() {
            val prepared = prepareLiteral("ER20328_23")
            assertFalse(prepared.query.contains('"'), "expected no quoting, got ${prepared.query}")
        }

        @Test
        fun `multiple identifiers are all required`() {
            val prepared = prepareLiteral("compare ER20328_23 and ER10044_07")
            assertEquals("+ER20328_23 +ER10044_07 compare and", prepared.query)
        }
    }

    @Nested
    @DisplayName("leaves alone what it should")
    inner class Untouched {

        @Test
        fun `prose with no identifier is unchanged and has no fallback`() {
            val prepared = prepareLiteral("error code timeout failure")
            assertEquals("error code timeout failure", prepared.query)
            assertNull(prepared.fallback)
            assertFalse(prepared.requiresTerms)
        }

        @Test
        fun `operators the caller typed become literal text`() {
            // The LITERAL contract: the caller cannot express an operator, and equally cannot trip
            // over one. Their `+` and `-` are escaped and searched for. A caller who wants operators
            // wants EXPRESSION mode — see `an expression is passed through byte for byte`.
            val prepared = prepareLiteral("+error -warning ER20328_23")
            assertEquals("+ER20328_23 \\+error \\-warning", prepared.query)
        }

        @Test
        fun `quotes the caller typed are searched for, not honoured`() {
            val prepared = prepareLiteral("\"null pointer exception\"")
            assertTrue(prepared.query.contains("\\\""), "quotes escaped: ${prepared.query}")
            assertNull(prepared.fallback, "no identifier, so nothing required and nothing to fall back to")
        }

        @Test
        fun `a shouted function word is not mistaken for an acronym`() {
            // ALL_CAPS would otherwise fire on AND and require the literal word, filtering to
            // documents that merely contain "and" — which is most of them.
            val prepared = prepareLiteral("ER20328_23 AND payment")
            assertEquals("+ER20328_23 AND payment", prepared.query)
        }

        @Test
        fun `a bare year is not an identifier`() {
            // Digits alone are far too common to force into every result set.
            val prepared = prepareLiteral("incidents in 2026")
            assertNull(prepared.fallback)
        }

        @Test
        fun `a short version fragment is not an identifier`() {
            val prepared = prepareLiteral("upgrade to v2 now")
            assertNull(prepared.fallback)
        }

        @Test
        fun `a blank query is returned as-is rather than rewritten`() {
            val prepared = prepareLiteral("   ")
            assertEquals("", prepared.query)
            assertNull(prepared.fallback)
        }
    }

    @Nested
    @DisplayName("identifier shapes seen in real corpora")
    inner class Shapes {

        @Test
        fun `a semantic version is required`() {
            // "3.5.2" has no letter, but "v3.5.2" does — and a release note asking about it means it.
            // '.' is not a Lucene operator, so nothing needs escaping here.
            assertEquals("+v3.5.2 upgrade to", prepareLiteral("upgrade to v3.5.2").query)
        }

        @Test
        fun `a short git sha is required and not quoted`() {
            assertEquals("+a1b2c3d commit", prepareLiteral("commit a1b2c3d").query)
        }

        @Test
        fun `a uuid is escaped, hyphens and all`() {
            assertEquals(
                "+550e8400\\-e29b\\-41d4\\-a716\\-446655440000 trace",
                prepareLiteral("trace 550e8400-e29b-41d4-a716-446655440000").query,
            )
        }

        @Test
        fun `an http status code phrase keeps the code required`() {
            assertEquals("+HTTP503 why do we see", prepareLiteral("why do we see HTTP503").query)
        }

        @Test
        fun `case is preserved so the analyzer decides folding, not us`() {
            assertTrue(prepareLiteral("ER20328_23").query.contains("ER20328_23"))
        }

        @Test
        fun `a token at exactly the minimum length is an identifier`() {
            assertEquals("+gpt4 about", prepareLiteral("about gpt4").query)
        }

        @Test
        fun `a token one character below the minimum is not`() {
            assertNull(prepareLiteral("about er1").fallback)
        }

        @Test
        fun `irregular whitespace does not produce empty terms`() {
            assertEquals("+ER20328_23 what causes", prepareLiteral("what   causes\tER20328_23").query)
        }

        @Test
        fun `a purely numeric token is never required even when long`() {
            // Order numbers that are all digits are indistinguishable from years, quantities and ids
            // the user did not mean to pin.
            assertNull(prepareLiteral("invoice 4405512 total").fallback)
        }
    }

    @Nested
    @DisplayName("fallback contract")
    inner class Fallback {

        @Test
        fun `a rewritten query always carries the original to fall back to`() {
            // This is what makes the rewrite safe: a required term that is absent from the corpus
            // degrades to the original query, never to an empty result.
            val prepared = prepareLiteral("what causes ER99999_00")
            assertTrue(prepared.requiresTerms)
            assertEquals("what causes ER99999_00", prepared.fallback)
        }

        @Test
        fun `an untouched query carries no fallback so no second search is issued`() {
            val prepared = prepareLiteral("payment service latency")
            assertNull(prepared.fallback)
        }
    }

    @Nested
    @DisplayName("the mode decides everything")
    inner class Modes {

        private fun expression(query: String) = FullTextQueryPreparation.prepare(
            query, FullTextQueryMode.EXPRESSION, CompositeRequiredTermExtractor(),
        )

        @Test
        fun `an expression is passed through byte for byte`() {
            // The caller composed it; touching it at all would make the surface unpredictable.
            val q = "+ER20328_23 -draft \"exact phrase\" payment~"
            assertEquals(q, expression(q).query)
            assertNull(expression(q).fallback, "nothing was required on the caller's behalf")
        }

        @Test
        fun `EXPRESSION never escapes, so a URL is the caller's problem`() {
            // Deliberate: under EXPRESSION the caller owns escaping. This is the query that fails to
            // parse in Lucene — an odd number of slashes opens an unterminated regex term.
            val url = "https://mail.google.com/mail/u/0/#all/19f459e8fa9d839d"
            assertEquals(url, expression(url).query)
        }

        @Test
        fun `LITERAL escapes that same URL so it can never fail to parse`() {
            val url = "https://mail.google.com/mail/u/0/#all/19f459e8fa9d839d"
            val prepared = prepareLiteral(url)
            assertFalse(
                prepared.query.contains(Regex("(?<!\\\\)/")),
                "every slash escaped, else the parser reads a regex term: ${prepared.query}",
            )
        }

        @Test
        fun `LITERAL never leaves a bare Lucene operator anywhere in the query`() {
            // The property that makes the mode worth having: no input can reach the parser as syntax.
            val nasty = "what is /etc/nginx.conf? cost: $50 (approx) [urgent] a^2 c:\\tmp"
            val prepared = prepareLiteral(nasty)
            val bare = Regex("(?<!\\\\)[+\\-&|!(){}\\[\\]^\"~*?:/]")
            // The leading + on a required term is ours and is intentional; strip those before checking.
            val withoutOurOperators = prepared.query.split(" ").joinToString(" ") { it.removePrefix("+") }
            assertFalse(
                bare.containsMatchIn(withoutOurOperators),
                "unescaped operator survived: ${prepared.query}",
            )
        }

        @Test
        fun `an extractor that requires nothing leaves LITERAL as pure escaped text`() {
            val prepared = FullTextQueryPreparation.prepare(
                "what causes ER20328_23", FullTextQueryMode.LITERAL, RequiredTermExtractor.NONE,
            )
            assertEquals("what causes ER20328_23", prepared.query)
            assertNull(prepared.fallback, "nothing required, so nothing to retreat from")
        }
    }

    @Nested
    @DisplayName("EXPRESSION recovers from a conjunction that matches nothing")
    inner class ExpressionRelaxation {

        private fun expression(query: String) = FullTextQueryPreparation.prepare(
            query, FullTextQueryMode.EXPRESSION, CompositeRequiredTermExtractor(),
        )

        @Test
        fun `a query requiring only ordinary words relaxes completely`() {
            // Verbatim from a benchmark run. `+registration +application +decide +how +long` matched
            // ZERO chunks on the real corpus; the same words unrequired put the target document at
            // rank 1 with 82 matching chunks. Retrieval went dark on a question with no identifier
            // in it at all.
            val prepared = expression("+registration +application +decide +how +long")
            assertEquals("+registration +application +decide +how +long", prepared.query,
                "the caller's expression still runs first, unaltered")
            assertEquals("registration application decide how long", prepared.fallback,
                "every required term was an ordinary word, so every + is dropped")
        }

        @Test
        fun `an identifier stays required, so an absent code still answers honestly`() {
            // The property that stops this becoming blanket relaxation: a code that is genuinely
            // missing from the corpus must keep returning nothing rather than junk.
            val prepared = expression("+ER99999_00 +payment +service")
            assertEquals("+ER99999_00 payment service", prepared.fallback,
                "ordinary words relax; the identifier does not")
        }

        @Test
        fun `nothing to relax means no second query`() {
            val prepared = expression("+ER20328_23 payment service")
            assertNull(prepared.fallback, "the only required term is an identifier — empty would be the truth")
        }

        @Test
        fun `an expression with no required terms is untouched`() {
            val prepared = expression("registration application decide")
            assertEquals("registration application decide", prepared.query)
            assertNull(prepared.fallback)
        }

        @Test
        fun `a bare plus is not treated as a required term`() {
            // "+" alone is punctuation the model emitted, not an operator with an operand.
            assertNull(expression("cost + freight").fallback)
        }
    }
}
