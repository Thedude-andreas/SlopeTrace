package com.slopetrace.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.slopetrace.data.model.SegmentType

@Database(
    entities = [TrackingPointEntity::class, LiftLabelEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackingDao(): TrackingDao

    class Converters {
        @TypeConverter
        fun segmentFromString(value: String): SegmentType = SegmentType.valueOf(value)

        @TypeConverter
        fun segmentToString(value: SegmentType): String = value.name
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracking_points ADD COLUMN segmentConfidence REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE tracking_points ADD COLUMN runId TEXT")
                db.execSQL("ALTER TABLE tracking_points ADD COLUMN liftId TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lift_labels` (
                        `sessionId` TEXT NOT NULL,
                        `liftId` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        PRIMARY KEY(`sessionId`, `liftId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "slope_trace.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
