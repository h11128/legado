package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookReviewBinding

@Dao
interface BookReviewBindingDao {

    @Query(
        """
        SELECT * FROM book_review_bindings
        WHERE contentBookUrl = :bookUrl
        ORDER BY sortOrder ASC, id ASC
        """
    )
    fun listByContentBookUrl(bookUrl: String): List<BookReviewBinding>

    /** Backward-compat: first binding by sortOrder (LIMIT 1). */
    @Query(
        """
        SELECT * FROM book_review_bindings
        WHERE contentBookUrl = :bookUrl
        ORDER BY sortOrder ASC, id ASC
        LIMIT 1
        """
    )
    fun getByContentBookUrl(bookUrl: String): BookReviewBinding?

    @Query(
        """
        SELECT * FROM book_review_bindings
        WHERE contentBookUrl = :contentBookUrl AND providerSourceUrl = :providerSourceUrl
        LIMIT 1
        """
    )
    fun getByContentAndProvider(
        contentBookUrl: String,
        providerSourceUrl: String,
    ): BookReviewBinding?

    @Query(
        """
        SELECT * FROM book_review_bindings
        WHERE contentName = :name AND contentAuthor = :author
        """
    )
    fun listByNameAuthor(name: String, author: String): List<BookReviewBinding>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(binding: BookReviewBinding): Long

    @Update
    fun update(binding: BookReviewBinding)

    @Query("DELETE FROM book_review_bindings WHERE contentBookUrl = :bookUrl")
    fun deleteByContentBookUrl(bookUrl: String)

    @Query(
        """
        DELETE FROM book_review_bindings
        WHERE contentBookUrl = :contentBookUrl AND providerSourceUrl = :providerSourceUrl
        """
    )
    fun deleteByContentAndProvider(contentBookUrl: String, providerSourceUrl: String)

    @Query("DELETE FROM book_review_bindings WHERE id = :id")
    fun deleteById(id: Long)
}
