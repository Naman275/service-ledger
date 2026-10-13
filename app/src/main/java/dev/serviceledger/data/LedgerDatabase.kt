package dev.serviceledger.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
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
    foreignKeys = [
        ForeignKey(entity = Customer::class, parentColumns = ["id"], childColumns = ["customerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Appliance::class, parentColumns = ["id"], childColumns = ["applianceId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("customerId"), Index("applianceId")],
)
data class ServiceVisit(
    @PrimaryKey val id: String,
    val customerId: String,
    val applianceId: String?,
    val serviceDay: Long,
    val work: String,
    val parts: String,
    val amountPaise: Long,
    val nextDueDay: Long?,
    val recordedAtMillis: Long = System.currentTimeMillis(),
    val photoId: String? = null,
    val paymentDueDay: Long? = null,
)

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(entity = ServiceVisit::class, parentColumns = ["id"],
        childColumns = ["serviceId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("serviceId")],
)
data class Payment(
    @PrimaryKey val id: String,
    val serviceId: String,
    val amountPaise: Long,
    val paymentDay: Long?,
    val method: String,
    val reference: String = "",
    val notes: String = "",
    val recordedAtMillis: Long = System.currentTimeMillis(),
    val voidedAtMillis: Long? = null,
    val voidReason: String = "",
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM customers ORDER BY name COLLATE NOCASE, id")
    suspend fun customers(): List<Customer>

    @Query("SELECT * FROM appliances ORDER BY name COLLATE NOCASE, id")
    suspend fun appliances(): List<Appliance>

    @Query("SELECT * FROM visits ORDER BY serviceDay DESC, id DESC")
    suspend fun visits(): List<ServiceVisit>

    @Query("SELECT * FROM payments ORDER BY recordedAtMillis DESC, id DESC")
    suspend fun payments(): List<Payment>
    @Query("SELECT * FROM visits WHERE id = :id") suspend fun visit(id: String): ServiceVisit?
    @Query("SELECT * FROM payments WHERE id = :id") suspend fun payment(id: String): Payment?
    @Query("SELECT * FROM appliances WHERE id = :id") suspend fun appliance(id: String): Appliance?
    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM payments WHERE serviceId = :id AND voidedAtMillis IS NULL")
    suspend fun received(id: String): Long
    @Query("SELECT EXISTS(SELECT 1 FROM payments WHERE serviceId = :id)")
    suspend fun hasPayments(id: String): Boolean
    @Query("SELECT (SELECT COUNT(*) FROM customers) + (SELECT COUNT(*) FROM appliances) + (SELECT COUNT(*) FROM visits) + (SELECT COUNT(*) FROM payments)")
    suspend fun recordCount(): Int
    @Query("SELECT EXISTS(SELECT 1 FROM customers WHERE id = :id)") suspend fun hasCustomer(id: String): Boolean

    @Upsert suspend fun saveCustomer(customer: Customer)
    @Upsert suspend fun saveAppliance(appliance: Appliance)
    @Upsert suspend fun saveVisit(visit: ServiceVisit)
    @Upsert suspend fun insertCustomers(customers: List<Customer>)
    @Upsert suspend fun insertAppliances(appliances: List<Appliance>)
    @Upsert suspend fun insertVisits(visits: List<ServiceVisit>)
    @Insert suspend fun insertPayment(payment: Payment)
    @Insert suspend fun insertPayments(payments: List<Payment>)
    @Query("UPDATE payments SET voidedAtMillis = :timestamp, voidReason = :reason WHERE id = :id")
    suspend fun voidPayment(id: String, timestamp: Long, reason: String)
    @Query("DELETE FROM customers WHERE id = :id") suspend fun deleteCustomer(id: String)
    @Query("DELETE FROM appliances WHERE id = :id") suspend fun deleteAppliance(id: String)
    @Query("DELETE FROM visits WHERE id = :id") suspend fun deleteVisit(id: String)
    @Query("DELETE FROM visits") suspend fun clearVisits()
    @Query("DELETE FROM payments") suspend fun clearPayments()
    @Query("DELETE FROM appliances") suspend fun clearAppliances()
    @Query("DELETE FROM customers") suspend fun clearCustomers()
}

@Database(
    entities = [Customer::class, Appliance::class, ServiceVisit::class, Payment::class],
    version = 3,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE visits_new (
                        id TEXT NOT NULL PRIMARY KEY, customerId TEXT NOT NULL, applianceId TEXT,
                        serviceDay INTEGER NOT NULL, work TEXT NOT NULL, parts TEXT NOT NULL,
                        amountPaise INTEGER NOT NULL, nextDueDay INTEGER, recordedAtMillis INTEGER NOT NULL,
                        photoId TEXT, paymentDueDay INTEGER,
                        FOREIGN KEY(customerId) REFERENCES customers(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(applianceId) REFERENCES appliances(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO visits_new
                    SELECT v.id, a.customerId, v.applianceId, v.serviceDay, v.work, v.parts,
                        v.amountPaise, v.nextDueDay, v.recordedAtMillis, v.photoId, NULL
                    FROM visits v JOIN appliances a ON a.id = v.applianceId
                """.trimIndent())
                // Preserve cumulative receipts without inventing their actual payment dates or methods.
                db.execSQL("CREATE TEMP TABLE opening_payments AS SELECT id, receivedPaise, recordedAtMillis FROM visits WHERE receivedPaise > 0")
                db.execSQL("DROP TABLE visits")
                db.execSQL("ALTER TABLE visits_new RENAME TO visits")
                db.execSQL("CREATE INDEX index_visits_customerId ON visits(customerId)")
                db.execSQL("CREATE INDEX index_visits_applianceId ON visits(applianceId)")
                db.execSQL("""
                    CREATE TABLE payments (
                        id TEXT NOT NULL PRIMARY KEY, serviceId TEXT NOT NULL, amountPaise INTEGER NOT NULL,
                        paymentDay INTEGER, method TEXT NOT NULL, reference TEXT NOT NULL, notes TEXT NOT NULL,
                        recordedAtMillis INTEGER NOT NULL, voidedAtMillis INTEGER, voidReason TEXT NOT NULL,
                        FOREIGN KEY(serviceId) REFERENCES visits(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX index_payments_serviceId ON payments(serviceId)")
                db.execSQL("""
                    INSERT INTO payments SELECT id, id, receivedPaise, NULL, 'OPENING', '', '',
                        recordedAtMillis, NULL, '' FROM opening_payments
                """.trimIndent())
                db.execSQL("DROP TABLE opening_payments")
            }
        }

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
