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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * These notes reach the LLM verbatim, inside the search tool's description. Getting them wrong is
 * not cosmetic: telling a model it has operators when the store escapes them teaches it to write
 * queries that cannot work, and every one of those failures looks like a retrieval problem.
 *
 * The behavioural proof that the EXPRESSION wording works lives in the assistant's prompt-eval
 * suite (`rag-identifier-required-term`, 3/3 on gpt-4.1-mini against 0/3 for the notes that shipped
 * before). These guard the properties that suite cannot see from another repository.
 */
class FullTextSyntaxNotesTest {

    @Test
    @DisplayName("EXPRESSION teaches the + that makes an identifier lookup precise")
    fun `expression notes ask for required identifiers`() {
        val notes = syntaxNotesFor(FullTextQueryMode.EXPRESSION)
        assertTrue(notes.contains("+"), "must name the operator")
        assertTrue(
            notes.contains("identifier", ignoreCase = true),
            "must say WHAT to require — 'Full support' named a capability and taught nothing",
        )
        assertTrue(
            notes.contains("Example:"),
            "the worked example is what took compliance from 2/3 to 3/3; two rounds of rule-tightening did not",
        )
    }

    @Test
    @DisplayName("EXPRESSION warns against requiring ordinary words")
    fun `expression notes guard the over-requiring failure`() {
        // The more dangerous failure: `+ER20328_23 +payment +service` looks precise and returns
        // nothing, because it discards every document that words the context differently.
        // Whitespace-normalised so re-wrapping the prose cannot fail the test for no reason.
        val notes = syntaxNotesFor(FullTextQueryMode.EXPRESSION).replace(Regex("\\s+"), " ")
        assertTrue(
            notes.contains("NEVER put + on an ordinary word"),
            "must warn against over-requiring, not just ask for the identifier",
        )
        assertTrue(
            notes.contains("never on more than one term"),
            "one + is the norm; several ordinary words required together is what matched nothing",
        )
    }

    @Test
    @DisplayName("EXPRESSION tells the model what to do when there is nothing to require")
    fun `expression notes cover the absent-identifier case`() {
        // The gap that caused the regression. The previous wording said "typically one + per query"
        // and "do not put + on ordinary words", and a model asked a question containing no
        // identifier still required every word of it — `+registration +application +decide +how
        // +long`, which matched zero chunks. Most questions have nothing to require, and the notes
        // have to say so outright rather than leave it implied.
        val notes = syntaxNotesFor(FullTextQueryMode.EXPRESSION).replace(Regex("\\s+"), " ")
        assertTrue(
            notes.contains("MOST QUESTIONS NEED NO + AT ALL"),
            "the dominant case must lead, not be inferred from a prohibition",
        )
        assertTrue(
            notes.contains("no identifier in the question, so no +"),
            "needs a worked example of a plain question, not only of an identifier one",
        )
    }

    @Test
    @DisplayName("LITERAL offers no operator it cannot honour")
    fun `literal notes promise no syntax`() {
        val notes = syntaxNotesFor(FullTextQueryMode.LITERAL)
        assertFalse(
            notes.contains("Lucene", ignoreCase = true),
            "under LITERAL every character is escaped; naming the syntax invites queries that search for punctuation",
        )
        assertTrue(
            notes.contains("NOT available", ignoreCase = true),
            "must say plainly that operators do not work — a model told nothing will try them",
        )
        assertTrue(
            notes.contains("exactly", ignoreCase = true) && notes.contains("identifier", ignoreCase = true),
            "must tell the model identifiers are handled for it, so it does not try to mark them",
        )
    }

    @Test
    @DisplayName("the two modes never advertise the same surface")
    fun `notes differ by mode`() {
        // The drift this whole indirection exists to prevent.
        assertFalse(
            syntaxNotesFor(FullTextQueryMode.LITERAL) == syntaxNotesFor(FullTextQueryMode.EXPRESSION),
            "notes are derived from the mode precisely so they cannot say the same thing",
        )
    }
}
