package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.SubjectAliasEntity

/** Read/insert access to subject aliases. No update or delete (see ActivityLedgerDatabase). */
@Dao
internal interface SubjectAliasDao {
    @Insert
    fun insert(row: SubjectAliasEntity)

    /**
     * Every alias of every ACTIVE subject, grouped by subject and, within one
     * subject, oldest first (id breaks ties).
     */
    @Query(
        "SELECT subject_aliases.* FROM subject_aliases " +
            "INNER JOIN subjects ON subjects.id = subject_aliases.subject_id " +
            "WHERE subjects.status = 'ACTIVE' " +
            "ORDER BY subject_aliases.subject_id ASC, subject_aliases.created_at ASC, subject_aliases.id ASC",
    )
    fun listForActiveSubjects(): List<SubjectAliasEntity>
}
