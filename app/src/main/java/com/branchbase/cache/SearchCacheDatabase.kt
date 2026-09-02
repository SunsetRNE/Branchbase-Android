package com.branchbase.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 搜索缓存独立数据库。
 */
@Database(entities = [SearchCacheEntity::class], version = 1, exportSchema = false)
abstract class SearchCacheDatabase : RoomDatabase() {

    abstract fun searchCacheDao(): SearchCacheDao

    companion object {
        @Volatile
        private var instance: SearchCacheDatabase? = null

        fun getInstance(context: Context): SearchCacheDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SearchCacheDatabase::class.java,
                    "search_cache.db",
                ).build().also { instance = it }
            }
        }
    }
}