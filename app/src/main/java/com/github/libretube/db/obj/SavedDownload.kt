package com.github.libretube.db.obj

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * PrimeTube: a video saved by the "Save to storage (MP4)" downloader as a normal
 * file in the user's phone storage or SD card. Kept here so the app can show a
 * download history with quick open/delete actions.
 */
@Serializable
@Entity(tableName = "savedDownload")
data class SavedDownload(
    @PrimaryKey val videoId: String,
    @ColumnInfo val title: String,
    @ColumnInfo val uploader: String,
    @ColumnInfo val uri: String,
    @ColumnInfo val fileName: String,
    @ColumnInfo val sizeBytes: Long,
    @ColumnInfo val duration: Long,
    @ColumnInfo val savedAt: Long
)
