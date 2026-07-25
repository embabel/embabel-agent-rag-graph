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
package com.embabel.agent.rag.graph.cyphergen

import com.embabel.agent.api.tool.AgenticTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.graph.mappers.NamedEntityDataRowMapper
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.common.textio.template.TemplateRenderer
import com.embabel.common.util.loggerFor
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.RowMapper
import org.drivine.query.QuerySpecification

/**
 * A compiled Cypher query ready for validation and execution.
 * Normalizes the query by converting literal escape sequences to actual characters,
 * handling cases where LLMs double-escape newlines in JSON output.
 */
data class CompiledCypherQuery(
    val query: String,
) {
    companion object {
        /**
         * Creates a CompiledCypherQuery with normalized whitespace.
         * Converts literal \n and \t sequences to actual newlines/tabs.
         */
        operator fun invoke(rawQuery: String): CompiledCypherQuery {
            val normalized = rawQuery
                .replace("\\n", "\n")
                .replace("\\t", "\t")
            return CompiledCypherQuery(normalized)
        }
    }
}

/**
 * Strategy interface for validating Cypher queries before execution.
 */
interface CypherQueryValidator {

    /**
     * Validates a Cypher query.
     * @param query The compiled query to validate
     * @param schema The data dictionary containing schema information
     * @throws IllegalArgumentException if the query is invalid
     */
    fun validate(query: CompiledCypherQuery, schema: DataDictionary)
}

/**
 * Safe tools for building Cypher queries based on the provided schema.
 *
 * Provides an agentic tool that uses LLM to generate and execute Cypher queries
 * against a graph database. The tool supports three query types:
 * - Count queries: Returns counts based on the user's question
 * - Entity queries: Returns entities matching the user's question
 * - Value queries: Returns literal values as strings based on the user's question
 *
 * All queries are validated against the provided schema before execution.
 *
 * Usage:
 * ```kotlin
 * val cypherTools = CypherQueryTools(schema, persistenceManager)
 * val tool = cypherTools.tool("Query the knowledge graph")
 * // Use tool with your agent
 * ```
 */
class CypherQueryTools @JvmOverloads constructor(
    val schema: DataDictionary,
    private val persistenceManager: PersistenceManager,
    val llm: LlmOptions = LlmOptions(),
    private val namedEntityDataMapper: RowMapper<NamedEntityData> = NamedEntityDataRowMapper(),
    private val validator: CypherQueryValidator = ChainedQueryValidator(
        NoMutationValidator,
        SchemaAdherenceValidator(strict = true),
    ),
    private val templateRenderer: TemplateRenderer = JinjavaTemplateRenderer(),
) {

    companion object {
        internal const val TEMPLATE_PREFIX = "cypher-query-tools/"
    }

    /**
     * Returns the main agentic tool that uses LLM to generate and execute Cypher queries.
     * This is the only public entry point - the internal LLM tools are not directly accessible.
     *
     * @param description Description of what this tool does in the context of your application
     * @param name Optional custom name for the tool (default: "cypher_query_tool")
     */
    @JvmOverloads
    fun tool(description: String, name: String = "cypher_query_tool"): Tool {
        val executor = CypherToolExecutor(
            schema = schema,
            persistenceManager = persistenceManager,
            namedEntityDataMapper = namedEntityDataMapper,
            validator = validator,
            templateRenderer = templateRenderer,
        )
        return AgenticTool(
            name = name,
            description = description,
        )
            .withParameter(
                Tool.Parameter.string("question", "The user's question to answer using the graph database")
            )
            .withSystemPrompt(buildSystemPrompt())
            .withTools(*executor.tools().toTypedArray())
            .withLlm(llm)
    }

    private fun buildSystemPrompt(): String {
        val entityTypes = schema.domainTypes.map { domainType ->
            mapOf(
                "label" to domainType.ownLabel,
                "description" to domainType.description,
                "properties" to domainType.properties.joinToString(", ") { it.name }
            )
        }

        val relationships = schema.allowedRelationships().map { rel ->
            mapOf(
                "fromLabel" to rel.from.ownLabel,
                "name" to rel.name,
                "toLabel" to rel.to.ownLabel,
                "description" to rel.description
            )
        }

        val model = mapOf(
            "entityTypes" to entityTypes,
            "relationships" to relationships
        )

        return templateRenderer.renderLoadedTemplate("${TEMPLATE_PREFIX}system-prompt", model)
    }
}

/**
 * Internal executor class that builds tools programmatically with externalized prompts.
 * This class is not exposed to users - they only interact with [CypherQueryTools.tool].
 */
internal class CypherToolExecutor(
    private val schema: DataDictionary,
    private val persistenceManager: PersistenceManager,
    private val namedEntityDataMapper: RowMapper<NamedEntityData>,
    private val validator: CypherQueryValidator,
    private val templateRenderer: TemplateRenderer,
) {

    private val logger = loggerFor<CypherToolExecutor>()
    private val objectMapper = jacksonObjectMapper()

    /**
     * Loads a tool description section from a Jinja template.
     */
    private fun loadToolText(toolName: String, section: String): String {
        return templateRenderer.renderLoadedTemplate(
            "${CypherQueryTools.TEMPLATE_PREFIX}tools/$toolName",
            mapOf("section" to section)
        ).trim()
    }

    /**
     * Returns all the tools provided by this executor.
     */
    fun tools(): List<Tool> = listOf(
        findEntityTool(),
        listAllTool(),
        getByIdTool(),
        describeLabelTool(),
        cypherCountTool(),
        queryForEntitiesTool(),
        queryForValuesTool(),
        queryForRowsTool(),
    )

    private fun findEntityTool(): Tool = Tool.create(
        name = "find_entity",
        description = loadToolText("find-entity", "description"),
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("label", loadToolText("find-entity", "param_label")),
            Tool.Parameter.string("searchTerm", loadToolText("find-entity", "param_searchTerm")),
        ),
    ) { input ->
        val params = objectMapper.readValue<Map<String, Any>>(input)
        val label = params["label"] as String
        val searchTerm = params["searchTerm"] as String
        Tool.Result.text(findEntity(label, searchTerm))
    }

    private fun listAllTool(): Tool = Tool.create(
        name = "list_all",
        description = loadToolText("list-all", "description"),
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("label", loadToolText("list-all", "param_label")),
            Tool.Parameter.integer("limit", loadToolText("list-all", "param_limit"), required = false),
        ),
    ) { input ->
        val params = objectMapper.readValue<Map<String, Any>>(input)
        val label = params["label"] as String
        val limit = (params["limit"] as? Number)?.toInt() ?: 50
        Tool.Result.text(listAll(label, limit))
    }

    private fun getByIdTool(): Tool = Tool.create(
        name = "get_by_id",
        description = loadToolText("get-by-id", "description"),
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("label", loadToolText("get-by-id", "param_label")),
            Tool.Parameter.string("id", loadToolText("get-by-id", "param_id")),
        ),
    ) { input ->
        val params = objectMapper.readValue<Map<String, Any>>(input)
        val label = params["label"] as String
        val id = params["id"] as String
        Tool.Result.text(getById(label, id))
    }

    private fun describeLabelTool(): Tool = Tool.create(
        name = "describe_label",
        description = loadToolText("describe-label", "description"),
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("label", loadToolText("describe-label", "param_label")),
        ),
    ) { input ->
        val params = objectMapper.readValue<Map<String, Any>>(input)
        val label = params["label"] as String
        Tool.Result.text(describeLabel(label))
    }

    private fun cypherCountTool(): Tool = Tool.create(
        name = "cypher_count",
        description = loadToolText("cypher-count", "description"),
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("cypher", loadToolText("cypher-count", "param_cypher")),
        ),
    ) { input ->
        val params = objectMapper.readValue<Map<String, Any>>(input)
        val cypher = params["cypher"] as String
        Tool.Result.text(cypherCount(cypher))
    }

    private fun queryForEntitiesTool(): Tool = Tool.create(
        name = "query_for_entities",
        description = loadToolText("query-for-entities", "description"),
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("cypher", loadToolText("query-for-entities", "param_cypher")),
        ),
    ) { input ->
        val params = objectMapper.readValue<Map<String, Any>>(input)
        val cypher = params["cypher"] as String
        Tool.Result.text(queryForEntities(cypher))
    }

    private fun queryForValuesTool(): Tool = Tool.create(
        name = "query_for_values",
        description = loadToolText("query-for-values", "description"),
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("cypher", loadToolText("query-for-values", "param_cypher")),
        ),
    ) { input ->
        val params = objectMapper.readValue<Map<String, Any>>(input)
        val cypher = params["cypher"] as String
        Tool.Result.text(queryForValues(cypher))
    }

    private fun queryForRowsTool(): Tool = Tool.create(
        name = "query_for_rows",
        description = loadToolText("query-for-rows", "description"),
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("cypher", loadToolText("query-for-rows", "param_cypher")),
        ),
    ) { input ->
        val params = objectMapper.readValue<Map<String, Any>>(input)
        val cypher = params["cypher"] as String
        Tool.Result.text(queryForRows(cypher))
    }

    // Tool implementations

    private fun findEntity(label: String, searchTerm: String): String {
        return try {
            val cypher = """
                MATCH (n:$label)
                WHERE toLower(n.name) CONTAINS toLower('$searchTerm')
                RETURN {
                    id: n.id,
                    name: n.name,
                    labels: labels(n)
                } AS result
                LIMIT 10
            """.trimIndent()

            logger.info("Finding entity with label={}, searchTerm={}", label, searchTerm)
            val compiled = CompiledCypherQuery(cypher)

            @Suppress("UNCHECKED_CAST")
            val results = persistenceManager.query(
                QuerySpecification
                    .withStatement(compiled.query)
                    .transform(Map::class.java)
            ) as List<Map<String, Any>>

            logger.info("Found {} matching entities", results.size)

            if (results.isEmpty()) {
                """
                    No $label found matching '$searchTerm'.
                    Try again with a different or shorter search term (e.g., just the surname).
                """.trimIndent()
            } else if (results.size == 1) {
                val result = results[0]
                val id = result["id"] ?: "unknown"
                val name = result["name"] ?: "unknown"
                "Found exact match: $name (id: $id). Use this id or name in your query."
            } else {
                "Found ${results.size} matching $label(s). Choose the most relevant one:\n" +
                        results.joinToString("\n") { result ->
                            val id = result["id"] ?: "unknown"
                            val name = result["name"] ?: "unknown"
                            "- $name (id: $id)"
                        } + "\n\nUse the chosen entity's id or exact name in your query."
            }
        } catch (e: Exception) {
            val errorMessage = "Error finding entity: ${e.message}"
            logger.error(errorMessage, e)
            """
                ERROR: $errorMessage
                Please check the label name and try again with a different search term.
            """.trimIndent()
        }
    }

    private fun listAll(label: String, limit: Int): String {
        return try {
            val cypher = """
                MATCH (n:$label)
                RETURN n.id AS id, n.name AS name
                ORDER BY n.name
                LIMIT $limit
            """.trimIndent()

            logger.info("Listing all {} (limit {})", label, limit)

            @Suppress("UNCHECKED_CAST")
            val results = persistenceManager.query(
                QuerySpecification
                    .withStatement(cypher)
                    .transform(Map::class.java)
            ) as List<Map<String, Any>>

            logger.info("Found {} {} entities", results.size, label)

            if (results.isEmpty()) {
                "No $label entities found in the database."
            } else {
                "Found ${results.size} $label entities:\n" +
                        results.joinToString("\n") { row ->
                            val id = row["id"] ?: "?"
                            val name = row["name"] ?: "?"
                            "- $name (id: $id)"
                        }
            }
        } catch (e: Exception) {
            val errorMessage = "Error listing $label: ${e.message}"
            logger.error(errorMessage, e)
            "ERROR: $errorMessage"
        }
    }

    private fun getById(label: String, id: String): String {
        return try {
            val cypher = """
                MATCH (n:$label {id: '$id'})
                RETURN properties(n) AS props, labels(n) AS labels
            """.trimIndent()

            logger.info("Getting {} by id={}", label, id)

            @Suppress("UNCHECKED_CAST")
            val results = persistenceManager.query(
                QuerySpecification
                    .withStatement(cypher)
                    .transform(Map::class.java)
            ) as List<Map<String, Any>>

            if (results.isEmpty()) {
                "No $label found with id '$id'."
            } else {
                val row = results[0]
                val props = row["props"] as? Map<*, *> ?: emptyMap<String, Any>()
                val labels = row["labels"] as? List<*> ?: emptyList<String>()

                "Found $label (labels: ${labels.joinToString(", ")}):\n" +
                        props.entries.joinToString("\n") { (k, v) -> "  $k: $v" }
            }
        } catch (e: Exception) {
            val errorMessage = "Error getting $label by id: ${e.message}"
            logger.error(errorMessage, e)
            "ERROR: $errorMessage"
        }
    }

    private fun describeLabel(label: String): String {
        return try {
            // Find outgoing relationships
            val outgoingCypher = """
                MATCH (n:$label)-[r]->(m)
                WITH type(r) AS relType, labels(m) AS targetLabels
                RETURN DISTINCT relType, targetLabels
                LIMIT 20
            """.trimIndent()

            // Find incoming relationships
            val incomingCypher = """
                MATCH (n:$label)<-[r]-(m)
                WITH type(r) AS relType, labels(m) AS sourceLabels
                RETURN DISTINCT relType, sourceLabels
                LIMIT 20
            """.trimIndent()

            logger.info("Describing label {}", label)

            @Suppress("UNCHECKED_CAST")
            val outgoing = persistenceManager.query(
                QuerySpecification
                    .withStatement(outgoingCypher)
                    .transform(Map::class.java)
            ) as List<Map<String, Any>>

            @Suppress("UNCHECKED_CAST")
            val incoming = persistenceManager.query(
                QuerySpecification
                    .withStatement(incomingCypher)
                    .transform(Map::class.java)
            ) as List<Map<String, Any>>

            val result = StringBuilder("Label: $label\n")

            if (outgoing.isNotEmpty()) {
                result.append("\nOutgoing relationships:\n")
                outgoing.forEach { row ->
                    val relType = row["relType"]
                    val targets = (row["targetLabels"] as? List<*>)?.joinToString(", ") ?: "?"
                    result.append("  -[:$relType]-> ($targets)\n")
                }
            }

            if (incoming.isNotEmpty()) {
                result.append("\nIncoming relationships:\n")
                incoming.forEach { row ->
                    val relType = row["relType"]
                    val sources = (row["sourceLabels"] as? List<*>)?.joinToString(", ") ?: "?"
                    result.append("  <-[:$relType]- ($sources)\n")
                }
            }

            if (outgoing.isEmpty() && incoming.isEmpty()) {
                result.append("No relationships found for this label.")
            }

            result.toString()
        } catch (e: Exception) {
            val errorMessage = "Error describing $label: ${e.message}"
            logger.error(errorMessage, e)
            "ERROR: $errorMessage"
        }
    }

    private fun cypherCount(cypher: String): String {
        return try {
            val compiled = CompiledCypherQuery(cypher)
            validator.validate(compiled, schema)

            logger.info("Executing count query: {}", cypher)
            val result = executeForCount(compiled)
            logger.info("Count result: {}", result)
            "Count: $result"
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            logger.error("Error executing count query: {}", errorMessage, e)

            // Provide specific guidance based on the error
            val guidance = when {
                errorMessage.contains("Expected exactly one result") ->
                    "Your query returns multiple rows or columns. Use query_for_rows for aggregations that return multiple results."

                errorMessage.contains("Invalid input") ->
                    "Cypher syntax error. Check your query syntax and try again."

                else -> "Please fix the query and try again."
            }

            """
                ERROR: $errorMessage
                $guidance
            """.trimIndent()
        }
    }

    private fun queryForEntities(cypher: String): String {
        return try {
            val compiled = CompiledCypherQuery(cypher)
            validator.validate(compiled, schema)

            logger.info("Executing entity query: {}", cypher)
            val results = executeForEntities(compiled)
            logger.info("Found {} entities", results.size)

            if (results.isEmpty()) {
                "No entities found."
            } else {
                "Found ${results.size} entities:\n" + results.joinToString("\n") { entity ->
                    "- ${entity.name} (${entity.labels().joinToString(", ")}): ${entity.description}"
                }
            }
        } catch (e: Exception) {
            val errorMessage = "Error executing entity query: ${e.message}"
            logger.error(errorMessage, e)
            """
                ERROR: $errorMessage
                Please fix the query and try again.
            """.trimIndent()
        }
    }

    private fun queryForValues(cypher: String): String {
        return try {
            val compiled = CompiledCypherQuery(cypher)
            validator.validate(compiled, schema)

            logger.info("Executing values query: {}", cypher)
            val results = executeForValues(compiled)
            logger.info("Found {} values", results.size)

            if (results.isEmpty()) {
                "No values found."
            } else {
                "Found ${results.size} values:\n" + results.joinToString("\n") { "- $it" }
            }
        } catch (e: Exception) {
            val errorMessage = "Error executing values query: ${e.message}"
            logger.error(errorMessage, e)
            """
                ERROR: $errorMessage
                Please fix the query and try again.
            """.trimIndent()
        }
    }

    private fun queryForRows(cypher: String): String {
        return try {
            val compiled = CompiledCypherQuery(cypher)
            validator.validate(compiled, schema)

            logger.info("Executing rows query: {}", cypher)
            val results = executeForRows(compiled)
            logger.info("Found {} rows", results.size)

            if (results.isEmpty()) {
                "No results found. This could mean:\n" +
                        "- No data matches your query criteria\n" +
                        "- The relationships you're querying don't exist in the database\n" +
                        "- Try a simpler query first to verify the data exists"
            } else {
                "Found ${results.size} rows:\n" + results.joinToString("\n") { row ->
                    row.entries.joinToString(", ") { (k, v) -> "$k: $v" }
                }
            }
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            logger.error("Error executing rows query: {}", errorMessage, e)

            val guidance = when {
                errorMessage.contains("Invalid input") ->
                    "Cypher syntax error. Check your query syntax."

                errorMessage.contains("not defined") || errorMessage.contains("unknown") ->
                    "Unknown label or relationship type. Check that you're using types from the schema."

                else -> "Please fix the query and try again."
            }

            """
                ERROR: $errorMessage
                $guidance
            """.trimIndent()
        }
    }

    private fun executeForCount(query: CompiledCypherQuery): Int {
        // First try direct integer result (for proper count queries)
        try {
            return persistenceManager.getOne(
                QuerySpecification
                    .withStatement(query.query)
                    .transform(Int::class.java)
            )
        } catch (e: Exception) {
            // If that fails, try to extract 'count' from a map result
            @Suppress("UNCHECKED_CAST")
            val results = persistenceManager.query(
                QuerySpecification
                    .withStatement(query.query)
                    .transform(Map::class.java)
            ) as List<Map<String, Any>>

            if (results.size == 1) {
                val row = results[0]
                // Try to find a count-like value
                val countValue = row["count"] ?: row["cnt"] ?: row.values.firstOrNull()
                if (countValue is Number) {
                    return countValue.toInt()
                }
            }
            // Re-throw original exception if we couldn't recover
            throw e
        }
    }

    private fun executeForEntities(query: CompiledCypherQuery): List<NamedEntityData> {
        return persistenceManager.query(
            QuerySpecification
                .withStatement(query.query)
                .mapWith(namedEntityDataMapper)
        )
    }

    private fun executeForValues(query: CompiledCypherQuery): List<String> {
        @Suppress("UNCHECKED_CAST")
        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(query.query)
                .transform(Map::class.java)
        ) as List<Map<String, Any>>

        return results.mapNotNull { row ->
            row["value"]?.toString()
        }
    }

    private fun executeForRows(query: CompiledCypherQuery): List<Map<String, Any>> {
        @Suppress("UNCHECKED_CAST")
        return persistenceManager.query(
            QuerySpecification
                .withStatement(query.query)
                .transform(Map::class.java)
        ) as List<Map<String, Any>>
    }
}
