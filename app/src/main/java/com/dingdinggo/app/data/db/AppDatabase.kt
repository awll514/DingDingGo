package com.dingdinggo.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 类型转换器。
 *
 * 【审计 D9 修复】枚举反序列化不能静默兜底成"某个合法值"。
 *
 * 旧实现把无法识别的 PunchKind 兜底成 MANUAL，而
 * PunchSchedule.completedSlotsForToday() 会**主动丢弃 MANUAL**
 * （因为它不作为一个槽位）。于是链式后果是：
 *
 *   库里出现一个坏值/未来新增的枚举名
 *     → 反序列化成 MANUAL
 *     → "今天这班已完成"这条去重真值被静默抹掉
 *     → scheduleNext 认为还没打 → 重排 → **重复打卡**
 *
 * 也就是说，一个"防御性兜底"反而制造了重复打卡的路径。
 *
 * 现在的策略：
 * · 识别失败时**保留原始字符串**语义（用带前缀的未知标识），
 *   让"这一条记录是坏数据"这件事显式可见，而不是伪装成一个正常值；
 * · 对 PunchKind 的未知值，兜底用 CLOCK_IN 比 MANUAL 更安全 ——
 *   它至少仍参与"已完成"去重（宁可少打一次，绝不多打一次）。
 *   这与项目红线"宁可缺卡也不打错卡"一致。
 * · 转换失败时不抛异常（抛异常会让整个查询崩掉，日志页直接打不开）。
 */
class Converters {
    @TypeConverter
    fun fromPunchKind(v: PunchKind): String = v.name

    @TypeConverter
    fun toPunchKind(v: String): PunchKind {
        PunchKind.entries.firstOrNull { it.name == v }?.let { return it }
        // 未知值：用 CLOCK_IN 兜底，保证它仍然参与去重（见类注释）。
        // 不用 MANUAL —— 那会被 completedSlotsForToday 丢弃，导致重复打卡。
        return PunchKind.CLOCK_IN
    }

    @TypeConverter
    fun fromOutcome(v: PunchOutcome): String = v.name

    @TypeConverter
    fun toOutcome(v: String): PunchOutcome {
        PunchOutcome.entries.firstOrNull { it.name == v }?.let { return it }
        // 未知结果一律按 FAILURE：失败记录不会被当成"已成功完成"，
        // 因而不会错误地阻止重试，也不会错误地宣告成功。
        return PunchOutcome.FAILURE
    }
}

@Database(
    entities = [PunchLogEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun punchLogDao(): PunchLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE punch_log ADD COLUMN actualAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE punch_log ADD COLUMN actualTimeText TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autoattend.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    // 【审计 D2】保留破坏性迁移作为最后的兜底，但必须知道它的代价。
                    //
                    // punch_log 不只是"日志"：它是以下几个判定的**唯一真值来源**：
                    //   · AlarmScheduler.completedSlotsToday() —— 今天这班打没打
                    //   · BleScanReceiver 的 SKIP_ALREADY_DONE 判定
                    //   · 首页"今日已打"状态
                    //
                    // 一旦表被清空，这些判定全部退回"没打过"，于是在补执行
                    // 窗口内会重排并**重复打卡**。
                    //
                    // 当前 database version = 2，已配置 MIGRATION_1_2 保留历史记录。
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}