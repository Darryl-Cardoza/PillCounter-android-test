package com.rite.pillcounting.core.room

import android.util.Base64
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.security.DatabaseKeyProvider
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Measures CRUD throughput for the SQLCipher-encrypted [AppDatabase] against a plain
 * (unencrypted) Room database of the same schema, to quantify the overhead introduced by
 * envelope-encrypted-passphrase SQLCipher. Not a pass/fail correctness test — logs timings via
 * Logcat (tag "DbPerf") and asserts only a generous sanity ceiling so a real regression
 * (e.g. an accidental O(n^2) path) still fails the build, without being a flaky micro-benchmark.
 *
 * Run standalone (not in the same suite run as other DB tests) for stable numbers, e.g.:
 *   ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.rite.pillcounting.core.room.EncryptedDbPerformanceInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDbPerformanceInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rowCount = 2000
    private val dbNamesToClean = mutableListOf<String>()

    @After
    fun tearDown() {
        dbNamesToClean.forEach { context.deleteDatabase(it) }
    }

    private fun buildEncrypted(dbName: String): AppDatabase {
        dbNamesToClean += dbName
        System.loadLibrary("sqlcipher")
        val dek = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)
        val passphrase = Base64.encodeToString(dek, Base64.NO_WRAP).toByteArray(Charsets.UTF_8)
        return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .allowMainThreadQueries()
            .build()
    }

    private fun buildPlain(dbName: String): AppDatabase {
        dbNamesToClean += dbName
        return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
    }

    private fun userRow(i: Int) = UserEntity(
        userId = "perf-user-$i",
        fName = "First$i",
        lName = "Last$i",
        avatarUrl = "https://example.test/avatar/$i.png",
        role = "pharmacist",
        isVerified = true,
        isProfileCompleted = true,
        pharmacyName = "Pharmacy $i",
        npiId = "NPI$i",
        language = "en",
        timezone = "Asia/Kolkata",
        notifications = true,
        country = "US",
        state = "CA"
    )

    @Test
    fun compare_write_and_read_throughput_encrypted_vs_plain() = runBlocking {
        val encryptedDb = buildEncrypted("perf_encrypted.db")
        val plainDb = buildPlain("perf_plain.db")

        val encryptedWriteMs = measureTimeMillis {
            repeat(rowCount) { i -> encryptedDb.userDao().insertIgnore(userRow(i)) }
        }
        val plainWriteMs = measureTimeMillis {
            repeat(rowCount) { i -> plainDb.userDao().insertIgnore(userRow(i)) }
        }

        val encryptedReadMs = measureTimeMillis {
            repeat(rowCount) { i -> encryptedDb.userDao().getByUserId("perf-user-$i") }
        }
        val plainReadMs = measureTimeMillis {
            repeat(rowCount) { i -> plainDb.userDao().getByUserId("perf-user-$i") }
        }

        encryptedDb.close()
        plainDb.close()

        val writeOverheadPct = if (plainWriteMs > 0) {
            ((encryptedWriteMs - plainWriteMs) * 100.0 / plainWriteMs)
        } else 0.0
        val readOverheadPct = if (plainReadMs > 0) {
            ((encryptedReadMs - plainReadMs) * 100.0 / plainReadMs)
        } else 0.0

        Log.i(
            "DbPerf",
            "rows=$rowCount | write: encrypted=${encryptedWriteMs}ms plain=${plainWriteMs}ms " +
                "overhead=${"%.1f".format(writeOverheadPct)}%% | " +
                "read: encrypted=${encryptedReadMs}ms plain=${plainReadMs}ms " +
                "overhead=${"%.1f".format(readOverheadPct)}%%"
        )

        // Sanity ceiling only — SQLCipher overhead is expected (encryption isn't free), but a
        // regression that makes it drastically (>20x) slower than plain SQLite would indicate a
        // real bug (e.g. re-deriving the key per row) rather than expected crypto cost.
        val ceiling = 20L
        assert(plainWriteMs == 0L || encryptedWriteMs < plainWriteMs * ceiling) {
            "Encrypted writes are >${ceiling}x slower than plain (encrypted=${encryptedWriteMs}ms, plain=${plainWriteMs}ms) — investigate for a real perf regression, not just crypto overhead."
        }
        assert(plainReadMs == 0L || encryptedReadMs < plainReadMs * ceiling) {
            "Encrypted reads are >${ceiling}x slower than plain (encrypted=${encryptedReadMs}ms, plain=${plainReadMs}ms) — investigate for a real perf regression, not just crypto overhead."
        }
    }

    /**
     * Real app bulk-insert code wraps multi-row writes in a single transaction (Room's default
     * per-DAO-call transaction otherwise commits once per row, which is not representative of
     * typical CRUD usage e.g. syncing a batch of transactions from the PMS). This isolates that
     * realistic case and confirms per-row overhead doesn't compound abnormally at 2000 rows.
     */
    @Test
    fun compare_single_transaction_bulk_write_encrypted_vs_plain() = runBlocking {
        val encryptedDb = buildEncrypted("perf_txn_encrypted.db")
        val plainDb = buildPlain("perf_txn_plain.db")

        val encryptedMs = measureTimeMillis {
            encryptedDb.withTransaction {
                repeat(rowCount) { i -> encryptedDb.userDao().insertIgnore(userRow(i)) }
            }
        }
        val plainMs = measureTimeMillis {
            plainDb.withTransaction {
                repeat(rowCount) { i -> plainDb.userDao().insertIgnore(userRow(i)) }
            }
        }

        encryptedDb.close()
        plainDb.close()

        val overheadPct = if (plainMs > 0) ((encryptedMs - plainMs) * 100.0 / plainMs) else 0.0
        Log.i(
            "DbPerf",
            "single-transaction bulk write, rows=$rowCount | encrypted=${encryptedMs}ms plain=${plainMs}ms " +
                "overhead=${"%.1f".format(overheadPct)}%%"
        )

        val ceiling = 20L
        assert(plainMs == 0L || encryptedMs < plainMs * ceiling) {
            "Single-transaction encrypted bulk write is >${ceiling}x slower than plain (encrypted=${encryptedMs}ms, plain=${plainMs}ms)."
        }
    }
}
