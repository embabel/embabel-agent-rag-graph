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

    @Nested
    @DisplayName("identifiers become required terms")
    inner class Identifiers {

        @Test
        fun `a bare error code is required`() {
            val prepared = FullTextQueryPreparation.prepare("ER20328_23")
            assertEquals("+ER20328_23", prepared.query)
        }

        @Test
        fun `an error code inside a sentence is required and the sentence still ranks`() {
            val prepared = FullTextQueryPreparation.prepare("what causes ER20328_23")
            assertEquals("+ER20328_23 what causes", prepared.query)
            assertTrue(prepared.wasRewritten)
        }

        @Test
        fun `trailing punctuation is not treated as a Lucene wildcard`() {
            // "ER20328_23?" would otherwise reach Lucene with a single-character wildcard attached,
            // which silently changes what matches.
            val prepared = FullTextQueryPreparation.prepare("what causes ER20328_23?")
            assertEquals("+ER20328_23 what causes", prepared.query)
        }

        @Test
        fun `a hyphenated part number is quoted so it stays one phrase`() {
            // A standard analyzer splits on '-', so an unquoted +PN-88421-C would require only the
            // first fragment. Quoting makes it a phrase over [pn, 88421, c].
            val prepared = FullTextQueryPreparation.prepare("order PN-88421-C")
            assertEquals("+\"PN-88421-C\" order", prepared.query)
        }

        @Test
        fun `underscores are not quoted because the analyzer joins across them`() {
            val prepared = FullTextQueryPreparation.prepare("ER20328_23")
            assertFalse(prepared.query.contains('"'), "expected no quoting, got ${prepared.query}")
        }

        @Test
        fun `multiple identifiers are all required`() {
            val prepared = FullTextQueryPreparation.prepare("compare ER20328_23 and ER10044_07")
            assertEquals("+ER20328_23 +ER10044_07 compare and", prepared.query)
        }
    }

    @Nested
    @DisplayName("leaves alone what it should")
    inner class Untouched {

        @Test
        fun `prose with no identifier is unchanged and has no fallback`() {
            val prepared = FullTextQueryPreparation.prepare("error code timeout failure")
            assertEquals("error code timeout failure", prepared.query)
            assertNull(prepared.fallback)
            assertFalse(prepared.wasRewritten)
        }

        @Test
        fun `a hand-written Lucene query is passed through untouched`() {
            // Someone who wrote operators meant them; rewriting would corrupt the query.
            val prepared = FullTextQueryPreparation.prepare("+error -warning ER20328_23")
            assertEquals("+error -warning ER20328_23", prepared.query)
            assertNull(prepared.fallback)
        }

        @Test
        fun `a phrase query is passed through untouched`() {
            val prepared = FullTextQueryPreparation.prepare("\"null pointer exception\"")
            assertNull(prepared.fallback)
        }

        @Test
        fun `boolean keywords suppress the rewrite`() {
            val prepared = FullTextQueryPreparation.prepare("ER20328_23 AND payment")
            assertEquals("ER20328_23 AND payment", prepared.query)
            assertNull(prepared.fallback)
        }

        @Test
        fun `a bare year is not an identifier`() {
            // Digits alone are far too common to force into every result set.
            val prepared = FullTextQueryPreparation.prepare("incidents in 2026")
            assertNull(prepared.fallback)
        }

        @Test
        fun `a short version fragment is not an identifier`() {
            val prepared = FullTextQueryPreparation.prepare("upgrade to v2 now")
            assertNull(prepared.fallback)
        }

        @Test
        fun `a blank query is returned as-is rather than rewritten`() {
            val prepared = FullTextQueryPreparation.prepare("   ")
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
            assertEquals("+\"v3.5.2\" upgrade to", FullTextQueryPreparation.prepare("upgrade to v3.5.2").query)
        }

        @Test
        fun `a short git sha is required and not quoted`() {
            assertEquals("+a1b2c3d commit", FullTextQueryPreparation.prepare("commit a1b2c3d").query)
        }

        @Test
        fun `a uuid is quoted because the analyzer splits on its hyphens`() {
            val uuid = "550e8400-e29b-41d4-a716-446655440000"
            assertEquals("+\"$uuid\" trace", FullTextQueryPreparation.prepare("trace $uuid").query)
        }

        @Test
        fun `an http status code phrase keeps the code required`() {
            assertEquals("+HTTP503 why do we see", FullTextQueryPreparation.prepare("why do we see HTTP503").query)
        }

        @Test
        fun `case is preserved so the analyzer decides folding, not us`() {
            assertTrue(FullTextQueryPreparation.prepare("ER20328_23").query.contains("ER20328_23"))
        }

        @Test
        fun `a token at exactly the minimum length is an identifier`() {
            assertEquals("+gpt4 about", FullTextQueryPreparation.prepare("about gpt4").query)
        }

        @Test
        fun `a token one character below the minimum is not`() {
            assertNull(FullTextQueryPreparation.prepare("about er1").fallback)
        }

        @Test
        fun `irregular whitespace does not produce empty terms`() {
            assertEquals("+ER20328_23 what causes", FullTextQueryPreparation.prepare("what   causes\tER20328_23").query)
        }

        @Test
        fun `a purely numeric token is never required even when long`() {
            // Order numbers that are all digits are indistinguishable from years, quantities and ids
            // the user did not mean to pin.
            assertNull(FullTextQueryPreparation.prepare("invoice 4405512 total").fallback)
        }
    }

    @Nested
    @DisplayName("fallback contract")
    inner class Fallback {

        @Test
        fun `a rewritten query always carries the original to fall back to`() {
            // This is what makes the rewrite safe: a required term that is absent from the corpus
            // degrades to the original query, never to an empty result.
            val prepared = FullTextQueryPreparation.prepare("what causes ER99999_00")
            assertTrue(prepared.wasRewritten)
            assertEquals("what causes ER99999_00", prepared.fallback)
        }

        @Test
        fun `an untouched query carries no fallback so no second search is issued`() {
            val prepared = FullTextQueryPreparation.prepare("payment service latency")
            assertNull(prepared.fallback)
        }
    }
}
