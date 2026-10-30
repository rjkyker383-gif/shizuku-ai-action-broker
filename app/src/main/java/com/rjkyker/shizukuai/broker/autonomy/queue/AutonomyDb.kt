package com.rjkyker.shizukuai.broker.autonomy.queue

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

@Entity(tableName = "tasks", indices = [Index("state"), Index("idem_key", unique = true)])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "args_json") val argsJson: String,
    val actor: String,
    val state: String,
    val priority: Int = 0,
    val attempts: Int = 0,
    @ColumnInfo(name = "max_attempts") val maxAttempts: Int = 3,
    @ColumnInfo(name = "idem_key") val idemKey: String? = null,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    @ColumnInfo(name = "not_before") val notBefore: Long = 0,
    @ColumnInfo(name = "expires_at") val expiresAt: Long = 0,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long = 0,
    @ColumnInfo(name = "result_json") val resultJson: String? = null,
    val error: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "task_events", indices = [Index("task_id")])
data class TaskEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "from_state") val fromState: String?,
    @ColumnInfo(name = "to_state") val toState: String,
    val note: String?,
    val ts: Long,
)

@Entity(tableName = "audit", indices = [Index("ts")])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val actor: String,
    val tool: String,
    val tier: String,
    val decision: String,
    val reason: String?,
    /** SHA-256 of the canonical args, never the args themselves (messages/secrets stay out of the log). */
    @ColumnInfo(name = "args_hash") val argsHash: String?,
    @ColumnInfo(name = "task_id") val taskId: Long? = null,
    val outcome: String? = null,
)

@Entity(tableName = "memory", indices = [Index("pending")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: String,
    /** 1 = proposed by AI, not used until the owner accepts it on-device. */
    val pending: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "used_count") val usedCount: Int = 0,
)

@Entity(tableName = "outbox", indices = [Index("state")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "target_pkg") val targetPkg: String,
    val recipient: String,
    val body: String,
    val actor: String,
    val state: String,
    val reason: String?,
    @ColumnInfo(name = "body_hash") val bodyHash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "decided_at") val decidedAt: Long = 0,
)

@Entity(tableName = "model_runs", indices = [Index("ts")])
data class ModelRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val model: String,
    val backend: String,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int,
    @ColumnInfo(name = "gen_tokens") val genTokens: Int,
    @ColumnInfo(name = "decode_tps") val decodeTps: Double,
    @ColumnInfo(name = "prefill_tps") val prefillTps: Double,
    @ColumnInfo(name = "peak_rss_kb") val peakRssKb: Long,
    val ok: Boolean,
    val stop: String?,
)

@Dao
interface TaskDao {
    @Insert fun insert(t: TaskEntity): Long
    @Update fun update(t: TaskEntity)
    @Query("SELECT * FROM tasks WHERE id = :id") fun get(id: Long): TaskEntity?
    @Query("SELECT * FROM tasks WHERE idem_key = :key LIMIT 1") fun byIdem(key: String): TaskEntity?
    @Query("SELECT * FROM tasks ORDER BY id DESC LIMIT :limit") fun recent(limit: Int): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE state IN ('QUEUED','WAITING_CONDITIONS') AND not_before <= :now AND lease_until < :now ORDER BY priority DESC, id ASC LIMIT 1")
    fun nextRunnable(now: Long): TaskEntity?
    @Query("SELECT * FROM tasks WHERE state IN ('RUNNING','VERIFYING') AND lease_until < :now") fun expiredLeases(now: Long): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE state IN ('QUEUED','WAITING_CONDITIONS','NEEDS_USER') AND expires_at > 0 AND expires_at < :now") fun expired(now: Long): List<TaskEntity>
    @Query("SELECT state, COUNT(*) AS n FROM tasks GROUP BY state") fun counts(): List<StateCount>
    @Query("SELECT COUNT(*) FROM tasks WHERE state IN ('QUEUED','WAITING_CONDITIONS','RUNNING','VERIFYING','NEEDS_USER')") fun activeCount(): Int
    @Insert fun event(e: TaskEventEntity): Long
    @Query("SELECT * FROM task_events WHERE task_id = :id ORDER BY id") fun events(id: Long): List<TaskEventEntity>
    @Query("DELETE FROM tasks WHERE state IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED') AND updated_at < :before") fun pruneDone(before: Long): Int
    @Query("DELETE FROM task_events WHERE task_id NOT IN (SELECT id FROM tasks)") fun pruneOrphanEvents(): Int
}

data class StateCount(val state: String, val n: Int)

@Dao
interface AuditDao {
    @Insert fun insert(a: AuditEntity): Long
    @Query("SELECT * FROM audit ORDER BY id DESC LIMIT :limit") fun recent(limit: Int): List<AuditEntity>
    @Query("SELECT COUNT(*) FROM audit") fun count(): Int
    @Query("DELETE FROM audit WHERE id <= (SELECT id FROM audit ORDER BY id DESC LIMIT 1 OFFSET :keep)") fun trim(keep: Int): Int
}

@Dao
interface MemoryDao {
    @Insert fun insert(m: MemoryEntity): Long
    @Update fun update(m: MemoryEntity)
    @Query("SELECT * FROM memory ORDER BY id DESC") fun all(): List<MemoryEntity>
    @Query("SELECT * FROM memory WHERE pending = 0 ORDER BY id DESC LIMIT :limit") fun accepted(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memory WHERE id = :id") fun get(id: Long): MemoryEntity?
    @Query("DELETE FROM memory WHERE id = :id") fun delete(id: Long): Int
    @Query("DELETE FROM memory") fun wipe(): Int
    @Query("SELECT COUNT(*) FROM memory") fun count(): Int
}

@Dao
interface OutboxDao {
    @Insert fun insert(o: OutboxEntity): Long
    @Update fun update(o: OutboxEntity)
    @Query("SELECT * FROM outbox WHERE id = :id") fun get(id: Long): OutboxEntity?
    @Query("SELECT * FROM outbox ORDER BY id DESC LIMIT :limit") fun recent(limit: Int): List<OutboxEntity>
    @Query("SELECT created_at FROM outbox WHERE state = 'HANDED_OFF' AND created_at > :since") fun sentSince(since: Long): List<Long>
    @Query("SELECT created_at FROM outbox WHERE state = 'HANDED_OFF' AND recipient = :recipient AND created_at > :since") fun sentToSince(recipient: String, since: Long): List<Long>
}

@Dao
interface ModelRunDao {
    @Insert fun insert(r: ModelRunEntity): Long
    @Query("SELECT * FROM model_runs ORDER BY id DESC LIMIT :limit") fun recent(limit: Int): List<ModelRunEntity>
    @Query("DELETE FROM model_runs WHERE id <= (SELECT id FROM model_runs ORDER BY id DESC LIMIT 1 OFFSET :keep)") fun trim(keep: Int): Int
}

@Database(
    entities = [TaskEntity::class, TaskEventEntity::class, AuditEntity::class, MemoryEntity::class,
        OutboxEntity::class, ModelRunEntity::class],
    version = 1, exportSchema = true,
)
abstract class AutonomyDb : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun audit(): AuditDao
    abstract fun memory(): MemoryDao
    abstract fun outbox(): OutboxDao
    abstract fun runs(): ModelRunDao

    companion object {
        @Volatile private var inst: AutonomyDb? = null
        fun get(ctx: Context): AutonomyDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AutonomyDb::class.java, "autonomy.db")
                // Additive migrations only (design doc). No destructive fallback: data is never silently dropped.
                .build().also { inst = it }
        }
    }
}
