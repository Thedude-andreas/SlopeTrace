package com.slopetrace.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.slopetrace.data.model.SegmentType

@Database(
    entities = [TrackingPointEntity::class],
    version = 1,
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

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "slope_trace.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
