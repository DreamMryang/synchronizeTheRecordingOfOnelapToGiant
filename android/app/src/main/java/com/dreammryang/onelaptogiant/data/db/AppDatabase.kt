package com.dreammryang.onelaptogiant.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SyncSessionEntity::class, SyncRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SyncSessionDao
    abstract fun recordDao(): SyncRecordDao
}
