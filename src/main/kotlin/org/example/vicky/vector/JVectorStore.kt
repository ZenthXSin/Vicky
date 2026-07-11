package org.example.vicky.vector

import io.github.jbellis.jvector.graph.GraphIndexBuilder
import io.github.jbellis.jvector.graph.GraphSearcher
import io.github.jbellis.jvector.graph.OnHeapGraphIndex
import io.github.jbellis.jvector.graph.RandomAccessVectorValues
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider
import io.github.jbellis.jvector.util.Bits
import io.github.jbellis.jvector.vector.VectorSimilarityFunction
import io.github.jbellis.jvector.vector.types.VectorFloat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager

class JVectorStore(dataDir: File = File("data/vector")) : VectorStore {

    private val vts = BuildScoreProvider.vts
    private val mutex = Mutex()

    private val db: Connection = run {
        dataDir.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${File(dataDir, "store.db").absolutePath}").also { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("""
                    CREATE TABLE IF NOT EXISTS vectors (
                        collection TEXT NOT NULL,
                        id TEXT NOT NULL,
                        vector BLOB NOT NULL,
                        payload TEXT NOT NULL DEFAULT '{}',
                        deleted INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (collection, id)
                    )
                """.trimIndent())
            }
        }
    }

    private inner class CollectionState(val dimension: Int) {
        val vectors = mutableListOf<FloatArray>()
        val idToIndex = HashMap<String, Int>()
        val indexToId = HashMap<Int, String>()
        val payloads = HashMap<String, Map<String, Any>>()
        var graphIndex: OnHeapGraphIndex? = null

        fun ravv(): RandomAccessVectorValues = object : RandomAccessVectorValues {
            override fun size() = vectors.size
            override fun dimension() = dimension
            override fun isValueShared() = false
            override fun getVector(i: Int): VectorFloat<*> = vts.createFloatVector(vectors[i])
            override fun copy() = this
        }
    }

    private val collections = HashMap<String, CollectionState>()

    override suspend fun upsert(collection: String, records: List<VectorRecord>) {
        if (records.isEmpty()) return
        mutex.withLock {
            val state = collections[collection] ?: error("Collection '$collection' 未初始化，请先调用 ensureCollection")
            db.autoCommit = false
            try {
                db.prepareStatement(
                    "INSERT OR REPLACE INTO vectors(collection,id,vector,payload,deleted) VALUES(?,?,?,?,0)"
                ).use { stmt ->
                    for (record in records) {
                        check(record.vector.size == state.dimension) {
                            "向量维度不匹配: 期望=${state.dimension}, 实际=${record.vector.size}"
                        }
                        val existingIdx = state.idToIndex[record.id]
                        if (existingIdx != null) {
                            state.vectors[existingIdx] = record.vector.copyOf()
                        } else {
                            val idx = state.vectors.size
                            state.vectors.add(record.vector.copyOf())
                            state.idToIndex[record.id] = idx
                            state.indexToId[idx] = record.id
                        }
                        state.payloads[record.id] = record.payload
                        stmt.setString(1, collection)
                        stmt.setString(2, record.id)
                        stmt.setBytes(3, record.vector.toBytes())
                        stmt.setString(4, record.payload.toJson())
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                db.commit()
            } catch (e: Exception) {
                db.rollback()
                throw e
            } finally {
                db.autoCommit = true
            }
            rebuildGraph(state)
        }
    }

    override suspend fun search(
        collection: String,
        vector: FloatArray,
        topK: Int,
        filter: Map<String, Any>?,
    ): List<SearchResult> = mutex.withLock {
        val state = collections[collection] ?: return@withLock emptyList()
        val graph = state.graphIndex ?: return@withLock emptyList()
        check(vector.size == state.dimension) { "查询向量维度不匹配: 期望=${state.dimension}, 实际=${vector.size}" }

        val result = GraphSearcher.search(
            vts.createFloatVector(vector),
            topK,
            state.ravv(),
            VectorSimilarityFunction.COSINE,
            graph,
            Bits.ALL,
        )

        result.nodes.mapNotNull { ns ->
            val id = state.indexToId[ns.node] ?: return@mapNotNull null
            val payload = state.payloads[id] ?: emptyMap()
            if (filter != null && !matchesFilter(payload, filter)) return@mapNotNull null
            SearchResult(id, ns.score, payload)
        }.sortedByDescending { it.score }
    }

    override suspend fun delete(collection: String, ids: List<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            val state = collections[collection] ?: return@withLock
            db.prepareStatement("DELETE FROM vectors WHERE collection=? AND id=?").use { stmt ->
                for (id in ids) {
                    stmt.setString(1, collection)
                    stmt.setString(2, id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            compact(state, ids.toSet())
            rebuildGraph(state)
        }
    }

    override suspend fun deleteByFilter(collection: String, filter: Map<String, Any>) {
        mutex.withLock {
            val state = collections[collection] ?: return@withLock
            val toDelete = state.payloads.keys.filter { id -> matchesFilter(state.payloads[id] ?: emptyMap(), filter) }.toSet()
            if (toDelete.isEmpty()) return@withLock
            db.prepareStatement("DELETE FROM vectors WHERE collection=? AND id=?").use { stmt ->
                for (id in toDelete) {
                    stmt.setString(1, collection)
                    stmt.setString(2, id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            compact(state, toDelete)
            rebuildGraph(state)
        }
    }

    override suspend fun scroll(
        collection: String,
        limit: Int,
        filter: Map<String, Any>?,
        offset: Int,
    ): List<VectorRecord> = mutex.withLock {
        val state = collections[collection] ?: return@withLock emptyList()
        state.payloads.entries
            .filter { (_, p) -> filter == null || matchesFilter(p, filter) }
            .drop(offset)
            .take(limit)
            .map { (id, p) ->
                val vec = state.idToIndex[id]?.let { state.vectors[it] } ?: floatArrayOf()
                VectorRecord(id, vec, p)
            }
    }

    override suspend fun scrollPayloadOnly(
        collection: String,
        limit: Int,
        filter: Map<String, Any>?,
        offset: Int,
    ): List<VectorRecord> = mutex.withLock {
        val state = collections[collection] ?: return@withLock emptyList()
        state.payloads.entries
            .filter { (_, p) -> filter == null || matchesFilter(p, filter) }
            .drop(offset)
            .take(limit)
            .map { (id, p) -> VectorRecord(id, floatArrayOf(), p) }
    }

    override suspend fun ensureCollection(collection: String, dimension: Int) {
        mutex.withLock {
            val existing = collections[collection]
            if (existing != null) {
                if (existing.dimension != dimension) {
                    collections.remove(collection)
                    db.prepareStatement("DELETE FROM vectors WHERE collection=?").use {
                        it.setString(1, collection)
                        it.executeUpdate()
                    }
                    loadCollection(collection, dimension)
                }
                return@withLock
            }
            loadCollection(collection, dimension)
        }
    }

    override suspend fun setPayload(collection: String, ids: List<String>, payload: Map<String, Any>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            val state = collections[collection] ?: return@withLock
            db.prepareStatement("UPDATE vectors SET payload=? WHERE collection=? AND id=?").use { stmt ->
                for (id in ids) {
                    val existing = state.payloads[id] ?: continue
                    val merged = existing + payload
                    state.payloads[id] = merged
                    stmt.setString(1, merged.toJson())
                    stmt.setString(2, collection)
                    stmt.setString(3, id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    override fun close() {
        if (!db.isClosed) db.close()
    }

    private fun loadCollection(collection: String, dimension: Int) {
        val state = CollectionState(dimension)
        collections[collection] = state
        db.prepareStatement(
            "SELECT id, vector, payload FROM vectors WHERE collection=? AND deleted=0"
        ).use { stmt ->
            stmt.setString(1, collection)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = rs.getString(1)
                    val idx = state.vectors.size
                    state.vectors.add(rs.getBytes(2).toFloats())
                    state.idToIndex[id] = idx
                    state.indexToId[idx] = id
                    state.payloads[id] = rs.getString(3).fromJson()
                }
            }
        }
        if (state.vectors.isNotEmpty()) {
            rebuildGraph(state)
            println("[Vicky] 从 SQLite 加载 '$collection': ${state.vectors.size} 条向量, 维度=$dimension")
        }
    }

    private fun compact(state: CollectionState, deletedIds: Set<String>) {
        val newVectors = mutableListOf<FloatArray>()
        val newIdToIndex = HashMap<String, Int>()
        val newIndexToId = HashMap<Int, String>()
        for ((id, oldIdx) in state.idToIndex) {
            if (id in deletedIds) continue
            val newIdx = newVectors.size
            newVectors.add(state.vectors[oldIdx])
            newIdToIndex[id] = newIdx
            newIndexToId[newIdx] = id
        }
        state.vectors.clear()
        state.vectors.addAll(newVectors)
        state.idToIndex.clear()
        state.idToIndex.putAll(newIdToIndex)
        state.indexToId.clear()
        state.indexToId.putAll(newIndexToId)
        state.payloads.keys.removeAll(deletedIds)
    }

    private fun rebuildGraph(state: CollectionState) {
        if (state.vectors.isEmpty()) { state.graphIndex = null; return }
        val ravv = state.ravv()
        GraphIndexBuilder(ravv, VectorSimilarityFunction.COSINE, 16, 100, 1.5f, 1.4f).use { builder ->
            state.graphIndex = builder.build(ravv)
        }
    }

    private fun matchesFilter(payload: Map<String, Any>, filter: Map<String, Any>) =
        filter.all { (k, v) ->
            val pv = payload[k]
            when (v) {
                is Boolean -> pv == v
                is Number -> pv?.toString()?.toDoubleOrNull() == v.toDouble()
                else -> pv?.toString() == v.toString()
            }
        }
}

private fun FloatArray.toBytes(): ByteArray =
    ByteBuffer.allocate(size * 4).also { for (f in this) it.putFloat(f) }.array()

private fun ByteArray.toFloats(): FloatArray =
    ByteBuffer.wrap(this).let { buf -> FloatArray(size / 4) { buf.getFloat() } }

private fun Map<String, Any>.toJson(): String = buildJsonObject {
    forEach { (k, v) ->
        when (v) {
            is Boolean -> put(k, v)
            is Long    -> put(k, v)
            is Int     -> put(k, v.toLong())
            is Double  -> put(k, v)
            is Float   -> put(k, v.toDouble())
            else       -> put(k, v.toString())
        }
    }
}.toString()

private fun String.fromJson(): Map<String, Any> {
    if (isBlank() || this == "{}") return emptyMap()
    return Json.parseToJsonElement(this).jsonObject.mapValues { (_, v) ->
        val p = v.jsonPrimitive
        when {
            p.isString              -> p.content
            p.booleanOrNull != null -> p.boolean
            p.longOrNull != null    -> p.long
            p.doubleOrNull != null  -> p.double
            else                    -> p.content
        }
    }
}
