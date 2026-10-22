<a href="https://hub.embabel.com"><img align="left" src="https://raw.githubusercontent.com/embabel/guide/main/logo.png" width="240"></a>

### **Embabel RAG Graph**

![Build](https://github.com/embabel/embabel-agent-rag-graph/actions/workflows/maven.yml/badge.svg) [![Embabel](https://img.shields.io/badge/Embabel-Agent_Framework-6C3483?style=for-the-badge)](https://github.com/embabel/embabel-agent) ![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white) ![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white) ![Apache Maven](https://img.shields.io/badge/Apache%20Maven-C71A36?style=for-the-badge&logo=Apache%20Maven&logoColor=white)

<br clear="left"/>

A graph-backed RAG store for the Embabel Agent framework: documents, sections, chunks and named
entities, with vector search, full-text search and context expansion. It runs on five databases
through [Drivine4j](https://github.com/liberation-data/drivine4j).

## Supported databases

| Database | Notes |
|---|---|
| **Neo4j** | The only engine where metadata-filtered vector search re-ranks by exact similarity (see [below](#filtered-vector-search-and-k)). |
| **FalkorDB** | Fastest on most workloads in [graph-bench](https://github.com/liberation-data/graph-bench). Unique constraints are a Redis command and a full-text index covers one property; Drivine handles both. |
| **Memgraph** | Needs the `memgraph/memgraph-mage` image, which has the vector and text search modules. |
| **Oracle** ![New](https://img.shields.io/badge/NEW-ff3e00?style=flat-square) | Oracle Database 23ai or later. No Cypher: Drivine reads with SQL/PGQ and writes rows. Needs the JDBC driver and the full Oracle Free image for full-text search. See [Oracle](#oracle). |
| **Postgres** ![New](https://img.shields.io/badge/NEW-ff3e00?style=flat-square) | Postgres 14 or later. No Cypher: Drivine reads and writes plain SQL over two tables. Needs the JDBC driver, and pgvector for vector search. See [Postgres](#postgres). |

## See it running

[Guide](https://github.com/embabel/guide) is a working reference: an Embabel agent that answers
questions about Embabel from its documentation, with this store behind it on any of these
databases. [Embabel Hub](https://hub.embabel.com) is its official front end.

<p align="center">
  <a href="https://www.youtube.com/watch?v=hY6ZFMIJdd4" target="_blank">
    <img src="https://raw.githubusercontent.com/embabel/guide/main/guide-demo.png" alt="The Voice, The Word, and The Wheel" width="700">
  </a>
</p>

## Usage

Add the dependency:

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-rag-graph</artifactId>
    <version>0.6.0-SNAPSHOT</version>
</dependency>
```

Configure a Drivine datasource and the RAG properties:

```yaml
database:
  datasources:
    graph:
      type: NEO4J          # or FALKORDB, MEMGRAPH; for ORACLE and POSTGRES see below
      host: localhost
      port: 7687
      user-name: neo4j
      password: secret
      database-name: neo4j

embabel:
  agent:
    rag:
      graph:
        content-element-index: embabel_content_index
        entity-index: embabel_entity_index
        content-element-full-text-index: embabel_content_fulltext_index
        entity-full-text-index: embabel_entity_fulltext_index
        filtered-search-over-fetch: 5      # beam multiplier for filtered vector search
        max-filtered-search-k: 500         # ceiling on the widened beam
```

Build the store, and the entity repository if you store named entities:

```kotlin
val gom = graphObjectManagerFactory.stateless("graph")

val store: GraphRagStore = GraphObjectManagerStore(
    gom = gom,
    persistenceManager = persistenceManager,
    properties = properties,            // GraphRagServiceProperties
    chunkerConfig = ContentChunker.Config(),
    chunkTransformer = ChunkTransformer.NO_OP,
    embeddingService = embeddingService,
)
store.provision()                       // indexes and constraints, idempotent

val entities = GraphObjectManagerEntityRepository(
    gom = gom,
    properties = properties,
    dataDictionary = dataDictionary,
    embeddingService = embeddingService,
    entitySchema = EntitySchemaProvisioner(persistenceManager, properties, { embeddingService }),
)
```

The database is whichever one the datasource names. Nothing database-specific is passed in, so moving
from one to another is a configuration change.

### Oracle

Oracle Database 23ai or later. Oracle does not speak Cypher: Drivine reads the graph with SQL/PGQ and
writes it as rows, and the store and the entity repository are the same classes as on the other
engines.

1. Add the JDBC driver. Drivine does not bring it:

   ```xml
   <dependency>
       <groupId>com.oracle.database.jdbc</groupId>
       <artifactId>ojdbc11</artifactId>
   </dependency>
   ```

2. Point the datasource at the database:

   ```yaml
   database:
     datasources:
       graph:
         type: ORACLE
         host: localhost
         port: 1521                  # the default
         database-name: FREEPDB1     # the service to connect to
         user-name: app
         password: secret
         default-graph-path: drivine # names the property graph and its tables; this is the default
   ```

   Drivine makes the graph's tables and the property graph over them in this user's schema, the first
   time a manager speaks to the graph. `provision()` adds the indexes, with a trigger for each vector
   index. The user needs the privileges to create them.

3. Build the store and the entity repository as above.

What differs on Oracle:

- `DrivineNamedEntityDataRepository` throws `UnsupportedOperationException` when it is constructed over
  an Oracle datasource: every statement in it is Cypher. Use `GraphObjectManagerEntityRepository`.
- `CypherQueryTools` and `LogicalQueryResolver` run Cypher, so they do not apply.
- Full-text search is Oracle Text, which is in the regular Oracle Free image and not the slim one. Its
  index holds what has been committed, so a search does not see its own transaction's writes.
- Oracle Text scores are on a different scale from Lucene's. A full-text score threshold tuned on
  Neo4j may cut differently, and has not been measured on Oracle.
- `reembedAll` over stored embeddings of mixed dimensions has not been verified on Oracle.

Drivine's README has the rest: how the graph is stored, connection pooling, and writing SQL by hand.

### Postgres

Postgres 14 or later. Postgres does not speak Cypher: Drivine keeps the graph in two tables, the same
ones as on Oracle, and reads and writes it in plain SQL. The store and the entity repository are the
same classes as on the other engines.

1. Add the JDBC driver. Drivine does not bring it:

   ```xml
   <dependency>
       <groupId>org.postgresql</groupId>
       <artifactId>postgresql</artifactId>
   </dependency>
   ```

2. Point the datasource at the database:

   ```yaml
   database:
     datasources:
       graph:
         type: POSTGRES
         host: localhost
         port: 5432                  # the default
         database-name: app          # the database to connect to
         user-name: app
         password: secret
         default-graph-path: drivine # names the graph's tables; this is the default
   ```

   Drivine makes the graph's tables the first time a manager speaks to the graph. `provision()` adds
   the indexes, and the `vector` and `pg_trgm` extensions they need where the user may create them.

3. Build the store and the entity repository as above.

What differs on Postgres:

- `DrivineNamedEntityDataRepository` throws `UnsupportedOperationException` when it is constructed over
  a Postgres datasource: every statement in it is Cypher. Use `GraphObjectManagerEntityRepository`.
- `CypherQueryTools` and `LogicalQueryResolver` run Cypher, so they do not apply.
- Vector search needs pgvector, which the `pgvector/pgvector` images have. A vector index is an HNSW
  index over the embeddings of the label's nodes that are of its dimension; a node embedded at another
  dimension is left out of it.
- A full-text index is a `pg_trgm` trigram index, which narrows a search to the nodes that hold its
  words. A search sees its own transaction's writes.
- Full-text scores count each term in the text, as on Oracle without Oracle Text, and are on a
  different scale from Lucene's. A full-text score threshold tuned on Neo4j may cut differently, and
  has not been measured on Postgres.
- `reembedAll` over stored embeddings of mixed dimensions has not been verified on Postgres.

### Filtered vector search and `k`

`k` handed to a vector index is the HNSW **search beam width**, not merely a row count, and `where {}`
predicates apply *after* the index yields. A metadata-filtered caller therefore receives roughly
`k × selectivity` rows, drawn from the globally-nearest rather than the nearest in scope. Measured on a
9k-vector, 1536-dim cosine index: a caller asking for 40 got back 25, holding only 25 of the true scoped
top 40 — recall 0.625.

`filtered-search-over-fetch` widens what the index is asked for while still returning `topK` rows, with
the trim applied after the filter. At the default of 5 the same query returns 40/40 at recall 1.0.

- Only the **filtered** path over-fetches. Unfiltered search already returns `topK` of `topK`.
- Set it to `1` to disable over-fetch entirely, which emits exactly what earlier versions did.
  Values below `1` are refused at startup rather than treated as off — a typo'd `0` would otherwise
  restore the diluted path silently.
- Raise it for highly selective filters; the right value is data-dependent.
- `max-filtered-search-k` bounds the cost, since the widened beam is re-ranked by exact similarity and
  that reads the full embedding off every candidate. A `topK` at or above the ceiling does not
  over-fetch rather than having its beam narrowed.

The exact re-rank is Neo4j-only; FalkorDB and Memgraph over-fetch and trim by their own approximate
score, so they gain row count but not ordering.

`Neo4jVectorRecallSearchKTest` measures all of the above against exhaustive `vector.similarity.cosine`
ground truth, and is the place to re-measure if the corpus or index configuration changes.

## How it works

**`GraphRagStore`** is the interface a consumer injects: ingestion and chunking, vector and full-text
search with metadata filters, context expansion, facets, `reembedAll`, and reading a document's
outline and a section's chunks.

**`GraphObjectManagerStore`** implements it on Drivine's `StatelessGraphObjectManager`. The content
model is a set of `@NodeFragment`s (documents, sections, chunks) and `@GraphView`s (a chunk with its
neighbours, a section with its parents, a document's whole tree). Drivine generates a typed query DSL
from them at build time. Saving, deleting, vector search (`gom.loadNearest`), full-text search
(`gom.loadMatching`) and walking the hierarchy are all calls on the object manager, so the store holds
no Cypher or SQL of its own, apart from one lookup described [below](#working-on-the-store-object-manager-first).

Supporting classes:

- **`GraphObjectManagerEntityRepository`**: named entities and their relationships, on the object
  manager, on every database.
- **`EntitySchemaProvisioner`** and **`GraphProvisioner`**: create the vector, full-text and range
  indexes and the unique constraints, through Drivine's schema managers. The entity indexes are rebuilt
  when the embedding model changes width.
- **`DrivineNativeFinder`**: loads any `@NodeFragment` or `@GraphView` type by id.
- **`DrivineNamedEntityDataRepository`**: the entity repository in hand-written Cypher, for the Cypher
  databases only.
- **`CypherQueryTools`** and **`LogicalQueryResolver`**: tools that let an LLM run validated,
  read-only Cypher, and named Cypher query files. Cypher databases only.

### Drivine

Drivine is why one store runs on five databases. A view says what to load, a chunk and what is
related to it, and not how; Drivine writes the statement in each database's language: Cypher for Neo4j,
FalkorDB and Memgraph, each in its own dialect, SQL/PGQ over two tables for Oracle, and plain SQL
over the same two tables for Postgres. Vector and full-text indexes differ more than queries do
(procedures on Neo4j, Redis commands on FalkorDB, MAGE modules on Memgraph, a vector index and an
Oracle Text index on Oracle, pgvector and `pg_trgm` indexes on Postgres) and Drivine creates and
queries each of them the same way.

Filtered vector search in the store is one call. `ChunkNodeQueryDsl` is generated from the `ChunkNode`
fragment, so the filter is checked by the compiler:

```kotlin
val nearest: List<Scored<ChunkNode>> = gom.loadNearest(
    ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE,
    embeddingService.embed("how do I rotate a refresh token?").toList(),
    topK = 10,
    threshold = 0.7,
) {
    where { query.rootDocumentId eq documentId }
}
```

That reads the chunk vector index on any of the five databases. The same call with no `where` is an
unfiltered search, and `gom.loadMatching` is its full-text counterpart.

It costs little. A load or a save is one statement and one round trip however many relationships it
carries. [graph-bench](https://github.com/liberation-data/graph-bench) runs the same Drivine code on
Neo4j, FalkorDB and Oracle over a graph of 1.1 million nodes and 4.5 million relationships, checks that
they give the same answers, and compares their speed: loading an issue with its repository, author,
assignees, topics and comments takes 1.5 to 3.5 ms on each of them.

## Working on the store: object manager first

`GraphObjectManagerStore` reads and writes through Drivine's `StatelessGraphObjectManager`: `@NodeFragment`
models, `@GraphView`s and the generated query DSL. Drivine writes the Cypher for the configured
engine, so one model runs on Neo4j, FalkorDB, Memgraph, Oracle and Postgres. A hand-written statement runs wherever
its author tried it.

- Express a new read or write as a fragment, a view or a DSL query first.
- Order by a property only with a range index that mirrors the ordering; Drivine warns when none does.
- Write Cypher only when the object manager has no reasonable way to say it, for features or for
  speed, and say why at the site.
- When Drivine cannot express something, prefer adding it to Drivine.
- A chunk loaded from the store carries a stamp (`@NodeStamp`), so `gom.save` of it is refused with
  `StaleObjectException` when the chunk changed after the load. `saveAll` does not compare stamps: a
  loaded chunk that is changed and written back is saved one at a time, as `reembedAll` does.

One site in the store is still Cypher: the entity-to-chunk lookup, because the entity label is
configuration and a fragment's labels are fixed at compile time. On Oracle and Postgres it loads what
the entity is related to through the object manager instead.

## Testing

Integration tests use Testcontainers (Neo4j by default; FalkorDB and Memgraph are activated via the `falkordb` and `memgraph` Spring profiles):

```bash
mvn test
```

The Oracle and Postgres tests are part of a plain run. They start Oracle Free
(`gvenzl/oracle-free:23-faststart`, which wants about 2 GB) and Postgres with pgvector
(`pgvector/pgvector:pg17`) in containers. To run one engine's tests alone:

```bash
mvn test -Dtest='Oracle*'
mvn test -Dtest='Postgres*'
```

## License

See LICENSE file in the root directory.
