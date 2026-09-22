package com.smsgateway.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SmsQueueEntity::class, EventLogEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun smsQueueDao(): SmsQueueDao

    abstract fun eventLogDao(): EventLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        /**
         * v1 → v2：给 localMessageId 加唯一索引。
         *
         * **这个迁移必须存在，不能靠 fallbackToDestructiveMigration 顶过去** ——
         * 那条兜底会把整张表清掉，而表里正是**还没上传**的短信，也就是用户还在等的验证码。
         *
         * 先删重复再建索引：历史库里可能已经有重复行（部分 ROM 会重投 SMS_RECEIVED），
         * 而重复行会让唯一索引建不起来 —— 迁移一旦失败就是启动即崩，用户只能清应用数据。
         * 保留 id 最小那行，也就是最早入库的那条。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM sms_queue WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM sms_queue GROUP BY localMessageId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sms_queue_localMessageId " +
                        "ON sms_queue(localMessageId)"
                )
            }
        }

        /**
         * v2 → v3：新增 event_log（重要事件运行记录，保留 7 天，见 [EventLogEntity]）。
         *
         * **纯新增表，一个字节都不动 sms_queue** —— 这是本迁移唯一让人放心的地方：
         * 那张表里是**还没上传**的短信，也就是用户还在等的验证码，动它就是动用户资产。
         *
         * 这段 DDL 不是手写的，是从 KSP 生成的 `AppDatabase_Impl.createAllTables()` 里
         * 逐字抄下来的。**不要凭记忆改它**：列名、类型、NOT NULL 三者只要有一处与
         * Room 的期望不符，启动时就会抛 `IllegalStateException: Migration didn't
         * properly handle`，而本项目刻意不用 `fallbackToDestructiveMigration`
         * （理由见 [MIGRATION_1_2]），所以迁移失败的用户只能清应用数据 —— 等于把
         * 待上传的验证码一起清掉。改这个文件之后请照抄一遍生成物。
         *
         * 不加 `DEFAULT` 子句：实体上没有一个字段带默认值，多写一个 DEFAULT
         * 就可能在校验时被判为不一致。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`level` TEXT NOT NULL, " +
                        "`sender` TEXT, " +
                        "`reason` TEXT, " +
                        "`smsId` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_log_createdAt` " +
                        "ON `event_log` (`createdAt`)"
                )
            }
        }

        /**
         * v3 → v4：event_log 增加 phone（这条短信是哪个号收到的）。
         *
         * **可空列**：设备读不到本机号码是常态（多数运营商不往卡里写），
         * 那种情况下这一列就是 NULL —— 不能写成 NOT NULL。
         *
         * 与 [MIGRATION_2_3] 一样，DDL 从 KSP 生成的 `AppDatabase_Impl.createAllTables()`
         * 里逐字抄。纯新增可空列，不动任何既有列，也不会丢行。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `event_log` ADD COLUMN `phone` TEXT")
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "sms_gateway.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}