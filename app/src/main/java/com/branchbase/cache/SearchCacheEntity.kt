package com.branchbase.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 搜索缓存实体。
 *
 * 独立缓存库：搜索缓存单独存数据库，与主业务数据分离。
 */
@Entity(tableName = "search_cache")
data class SearchCacheEntity(
    @PrimaryKey val key: String,
    val type: String,
    val data: String,
    val createdAt: Long,
    val expireAt: Long,
)