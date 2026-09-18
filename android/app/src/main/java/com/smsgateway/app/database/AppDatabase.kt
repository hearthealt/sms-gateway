package com.smsgateway.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SmsQueueEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun smsQueueDao(): SmsQueueDao

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

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "sms_gateway.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}