package dev.serviceledger.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val address: String,
)

@Entity(
    tableName = "appliances",
    foreignKeys = [ForeignKey(
        entity = Customer::class,
        parentColumns = ["id"],
        childColumns = ["customerId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("customerId")],
)
data class Appliance(
    @PrimaryKey val id: String,
    val customerId: String,
    val name: String,
    val model: String,
    val serial: String,
    val initialDueDay: Long?,
    val photoId: String? = null,
)

@Entity(
    tableName = "visits",
    foreignKeys = [ForeignKey(
        entity = Appliance::class,
        parentColumns = ["id"],
        childColumns = ["applianceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("applianceId")],
)
data class ServiceVisit(
    @PrimaryKey val id: String,
    val applianceId: String,
    val serviceDay: Long,
    val work: String,
    val parts: String,
    val amountPaise: Long,
    val receivedPaise: Long,
    val nextDueDay: Long?,
    val recordedAtMillis: Long = System.currentTimeMillis(),
    val photoId: String? = null,
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM customers ORDER BY name COLLATE NOCASE, id")
    suspend fun customers(): List<Customer>

    @Query("SELECT * FROM appliances ORDER BY name COLLATE NOCASE, id")
    suspend fun appliances(): List<Appliance>

    @Query("SELECT * FROM visits ORDER BY serviceDay DESC, id DESC")
    suspend fun visits(): List<ServiceVisit>

    @Upsert suspend fun saveCustomer(customer: Customer)
    @Upsert suspend fun saveAppliance(appliance: Appliance)
    @Upsert suspend fun saveVisit(visit: ServiceVisit)
    @Upsert suspend fun insertCustomers(customers: List<Customer>)
    @Upsert suspend fun insertAppliances(appliances: List<Appliance>)
    @Upsert suspend fun insertVisits(visits: List<ServiceVisit>)
    @Query("DELETE FROM customers WHERE id = :id") suspend fun deleteCustomer(id: String)
    @Query("DELETE FROM appliances WHERE id = :id") suspend fun deleteAppliance(id: String)
    @Query("DELETE FROM visits WHERE id = :id") suspend fun deleteVisit(id: String)
    @Query("DELETE FROM visits") suspend fun clearVisits()
    @Query("DELETE FROM appliances") suspend fun clearAppliances()
    @Query("DELETE FROM customers") suspend fun clearCustomers()
}

@Database(
    entities = [Customer::class, Appliance::class, ServiceVisit::class],
    version = 2,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE appliances ADD COLUMN photoId TEXT")
                db.execSQL("""
                    CREATE TABLE visits_new (
                        id TEXT NOT NULL PRIMARY KEY, applianceId TEXT NOT NULL,
                        serviceDay INTEGER NOT NULL, work TEXT NOT NULL, parts TEXT NOT NULL,
                        amountPaise INTEGER NOT NULL, receivedPaise INTEGER NOT NULL,
                        nextDueDay INTEGER, recordedAtMillis INTEGER NOT NULL, photoId TEXT,
                        FOREIGN KEY(applianceId) REFERENCES appliances(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO visits_new
                    SELECT id, applianceId, serviceDay, work, parts, amountPaise,
                        CASE WHEN paid = 1 THEN amountPaise ELSE 0 END,
                        nextDueDay, recordedAtMillis, NULL FROM visits
                """.trimIndent())
                db.execSQL("DROP TABLE visits")
                db.execSQL("ALTER TABLE visits_new RENAME TO visits")
                db.execSQL("CREATE INDEX index_visits_applianceId ON visits(applianceId)")
            }
        }
    }
}
