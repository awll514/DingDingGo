package com.redmi.autoattend.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromPunchKind(v: PunchKind): String = v.name

    @TypeConverter
    fun toPunchKind(v: String): PunchKind =
        runCatching { PunchKind.valueOf(v) }.getOrDefault(PunchKind.MANUAL)

    @TypeConverter
    fun fromOutcome(v: PunchOutcome): String = v.name

    @TypeConverter
    fun toOutcome(v: String): PunchOutcome =
        runCatching { PunchOutcome.valueOf(v) }.getOrDefault(PunchOutcome.FAILURE)
}

@Database(
    entities = [PunchLogEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun punchLogDao(): PunchLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autoattend.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}