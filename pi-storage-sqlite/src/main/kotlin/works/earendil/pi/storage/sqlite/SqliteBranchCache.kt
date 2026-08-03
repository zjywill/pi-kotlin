package works.earendil.pi.storage.sqlite

import java.sql.Connection
import works.earendil.pi.agent.session.SessionErrorCode
import works.earendil.pi.agent.session.SessionException
import works.earendil.pi.ai.uuidv7

internal data class CachedBranch(
    val branchId: String,
    val leafSequence: Int,
)

internal fun Connection.readCachedBranch(
    sessionId: String,
    leafId: String,
): CachedBranch? =
    prepareStatement(
        """
        SELECT branch_id, entry_seq
        FROM branch_entries
        WHERE session_id = ? AND entry_id = ?
        ORDER BY branch_id
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, leafId)
        statement.executeQuery().use { rows ->
            if (rows.next()) {
                CachedBranch(rows.getString("branch_id"), rows.getInt("entry_seq"))
            } else {
                null
            }
        }
    }

internal fun Connection.readCachedBranchRows(
    sessionId: String,
    branch: CachedBranch,
    newestFirst: Boolean = false,
    startSequence: Int = 0,
): List<SessionEntryRow> =
    prepareStatement(
        """
        SELECT e.id, e.entry_seq, e.parent_id, e.type, e.timestamp, e.payload
        FROM branch_entries b
        JOIN session_entries e
          ON e.session_id = b.session_id AND e.id = b.entry_id
        WHERE b.session_id = ? AND b.branch_id = ?
          AND b.entry_seq BETWEEN ? AND ?
        ORDER BY b.entry_seq ${if (newestFirst) "DESC" else "ASC"}
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, branch.branchId)
        statement.setInt(3, startSequence)
        statement.setInt(4, branch.leafSequence)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(rows.toEntryRow())
                }
            }
        }
    }

internal fun Connection.readCachedEntriesByType(
    sessionId: String,
    branch: CachedBranch,
    type: String,
): List<SessionEntryRow> =
    prepareStatement(
        """
        SELECT e.id, e.entry_seq, e.parent_id, e.type, e.timestamp, e.payload
        FROM session_entries e INDEXED BY idx_session_entries_session_type
        CROSS JOIN branch_entries b
        WHERE e.session_id = ? AND e.type = ?
          AND b.session_id = e.session_id AND b.entry_id = e.id
          AND b.branch_id = ? AND b.entry_seq <= ?
        ORDER BY e.entry_seq DESC
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, type)
        statement.setString(3, branch.branchId)
        statement.setInt(4, branch.leafSequence)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(rows.toEntryRow())
                }
            }
        }
    }

internal fun Connection.readCachedEntrySequence(
    sessionId: String,
    branchId: String,
    entryId: String,
): Int? =
    prepareStatement(
        """
        SELECT entry_seq
        FROM branch_entries
        WHERE session_id = ? AND branch_id = ? AND entry_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, branchId)
        statement.setString(3, entryId)
        statement.executeQuery().use { rows ->
            if (rows.next()) rows.getInt("entry_seq") else null
        }
    }

internal fun Connection.rebuildCachedBranch(
    sessionId: String,
    leafId: String,
    branchIdToReplace: String? = null,
) {
    val existingBranch =
        prepareStatement(
            "SELECT branch_id FROM branch_tips WHERE session_id = ? AND tip_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, leafId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString("branch_id") else null
            }
        }
    setOfNotNull(branchIdToReplace, existingBranch).forEach { branchId ->
        prepareStatement("DELETE FROM branch_tips WHERE session_id = ? AND branch_id = ?").use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, branchId)
            statement.executeUpdate()
        }
        prepareStatement("DELETE FROM branch_entries WHERE session_id = ? AND branch_id = ?").use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, branchId)
            statement.executeUpdate()
        }
    }

    val branchId = uuidv7()
    val inserted =
        prepareStatement(
            """
            WITH RECURSIVE path(id, entry_seq, parent_id) AS (
                SELECT id, entry_seq, parent_id
                FROM session_entries
                WHERE session_id = ? AND id = ?
                UNION ALL
                SELECT parent.id, parent.entry_seq, parent.parent_id
                FROM session_entries parent
                JOIN path child ON child.parent_id = parent.id
                WHERE parent.session_id = ?
            )
            INSERT INTO branch_entries (session_id, branch_id, entry_id, entry_seq)
            SELECT ?, ?, id, entry_seq FROM path
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, leafId)
            statement.setString(3, sessionId)
            statement.setString(4, sessionId)
            statement.setString(5, branchId)
            statement.executeUpdate()
        }
    if (inserted == 0) {
        throw SessionException(SessionErrorCode.NOT_FOUND, "Entry $leafId not found")
    }
    prepareStatement(
        "INSERT INTO branch_tips (session_id, tip_id, branch_id) VALUES (?, ?, ?)",
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, leafId)
        statement.setString(3, branchId)
        statement.executeUpdate()
    }
}

internal fun Connection.appendEntryToBranchCache(
    sessionId: String,
    entryId: String,
    entrySequence: Int,
    parentId: String?,
) {
    if (parentId == null) {
        val branchId = uuidv7()
        insertBranchEntry(sessionId, branchId, entryId, entrySequence)
        insertBranchTip(sessionId, entryId, branchId)
        return
    }

    val tipBranch =
        prepareStatement(
            "SELECT branch_id FROM branch_tips WHERE session_id = ? AND tip_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, parentId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString("branch_id") else null
            }
        }
    if (tipBranch != null) {
        extendCachedBranch(sessionId, tipBranch, parentId, entryId, entrySequence)
        return
    }

    var source =
        prepareStatement(
            """
            SELECT branch_id, entry_seq
            FROM branch_entries
            WHERE session_id = ? AND entry_id = ?
            ORDER BY branch_id
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, parentId)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    rows.getString("branch_id") to rows.getInt("entry_seq")
                } else {
                    null
                }
            }
        }
    if (source == null) {
        rebuildCachedBranch(sessionId, parentId)
        source =
            prepareStatement(
                "SELECT branch_id, entry_seq FROM branch_entries WHERE session_id = ? AND entry_id = ? LIMIT 1",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, parentId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        rows.getString("branch_id") to rows.getInt("entry_seq")
                    } else {
                        null
                    }
                }
            }
    }
    val (sourceBranchId, sourceSequence) =
        source
            ?: throw SessionException(
                SessionErrorCode.INVALID_SESSION,
                "Branch cache repair did not create entry $parentId",
            )
    val branchId = uuidv7()
    prepareStatement(
        """
        INSERT INTO branch_entries (session_id, branch_id, entry_id, entry_seq)
        SELECT session_id, ?, entry_id, entry_seq
        FROM branch_entries
        WHERE session_id = ? AND branch_id = ? AND entry_seq <= ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, branchId)
        statement.setString(2, sessionId)
        statement.setString(3, sourceBranchId)
        statement.setInt(4, sourceSequence)
        statement.executeUpdate()
    }
    insertBranchEntry(sessionId, branchId, entryId, entrySequence)
    insertBranchTip(sessionId, entryId, branchId)
}

private fun Connection.extendCachedBranch(
    sessionId: String,
    branchId: String,
    parentId: String,
    entryId: String,
    entrySequence: Int,
) {
    insertBranchEntry(sessionId, branchId, entryId, entrySequence)
    val updated =
        prepareStatement(
            """
            UPDATE branch_tips
            SET tip_id = ?
            WHERE session_id = ? AND branch_id = ? AND tip_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, entryId)
            statement.setString(2, sessionId)
            statement.setString(3, branchId)
            statement.setString(4, parentId)
            statement.executeUpdate()
        }
    if (updated != 1) {
        throw SessionException(
            SessionErrorCode.INVALID_SESSION,
            "Branch tip $parentId changed during append",
        )
    }
}

private fun Connection.insertBranchEntry(
    sessionId: String,
    branchId: String,
    entryId: String,
    entrySequence: Int,
) {
    prepareStatement(
        "INSERT INTO branch_entries (session_id, branch_id, entry_id, entry_seq) VALUES (?, ?, ?, ?)",
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, branchId)
        statement.setString(3, entryId)
        statement.setInt(4, entrySequence)
        statement.executeUpdate()
    }
}

private fun Connection.insertBranchTip(
    sessionId: String,
    tipId: String,
    branchId: String,
) {
    prepareStatement(
        "INSERT INTO branch_tips (session_id, tip_id, branch_id) VALUES (?, ?, ?)",
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, tipId)
        statement.setString(3, branchId)
        statement.executeUpdate()
    }
}
