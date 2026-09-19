package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.libretube.db.obj.SavedDownload

@Dao
interface SavedDownloadDao {
    @Query("SELECT * FROM savedDownload ORDER BY savedAt DESC")
    suspend fun getAll(): List<SavedDownload>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(savedDownload: SavedDownload)

    @Query("DELETE FROM savedDownload WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}
