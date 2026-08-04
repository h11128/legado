package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ChangeSourceChapterProbe

@Dao
interface ChangeSourceChapterProbeDao {

    @Query(
        """
        select * from change_source_chapter_probe
        where name = :name and author = :author and chapterKey = :chapterKey
        """
    )
    fun list(name: String, author: String, chapterKey: String): List<ChangeSourceChapterProbe>

    @Query(
        """
        select * from change_source_chapter_probe
        where name = :name and author = :author and origin = :origin and chapterKey = :chapterKey
        limit 1
        """
    )
    fun get(
        name: String,
        author: String,
        origin: String,
        chapterKey: String,
    ): ChangeSourceChapterProbe?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(vararg rows: ChangeSourceChapterProbe)

    @Query("delete from change_source_chapter_probe where time < :time")
    fun clearExpired(time: Long)
}
