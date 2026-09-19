package p2pgate.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Database
import androidx.room.Upsert

/** Opaque blob row: the whole [DealSnapshot] JSON under the deal id key. */
@Entity(tableName = "deals")
data class DealEntity(
    @PrimaryKey val dealId: String,
    val blob: String,
    val updatedAtEpochMs: Long,
)

@Dao
interface DealDao {
    @Upsert
    suspend fun upsert(entity: DealEntity)

    @Query("SELECT blob FROM deals WHERE dealId = :dealId")
    suspend fun get(dealId: String): String?

    @Query("SELECT blob FROM deals ORDER BY updatedAtEpochMs DESC")
    suspend fun all(): List<String>

    @Query("DELETE FROM deals WHERE dealId = :dealId")
    suspend fun delete(dealId: String)
}

@Database(entities = [DealEntity::class], version = 1, exportSchema = false)
abstract class DealDatabase : RoomDatabase() {
    abstract fun dealDao(): DealDao
}

/** Room-backed [DealSnapshotStore] — thin; all logic lives in the codec. */
class RoomDealSnapshotStore(private val db: DealDatabase) : DealSnapshotStore {
    override suspend fun upsert(snapshot: DealSnapshot) {
        db.dealDao().upsert(
            DealEntity(snapshot.dealId, DealSnapshot.encode(snapshot), snapshot.updatedAtEpochMs),
        )
    }

    override suspend fun get(dealId: String): DealSnapshot? =
        db.dealDao().get(dealId)?.let(DealSnapshot::decode)

    override suspend fun all(): List<DealSnapshot> = db.dealDao().all().map(DealSnapshot::decode)

    override suspend fun delete(dealId: String) {
        db.dealDao().delete(dealId)
    }
}
