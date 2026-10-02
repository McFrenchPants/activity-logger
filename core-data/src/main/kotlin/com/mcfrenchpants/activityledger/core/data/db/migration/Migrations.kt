package com.mcfrenchpants.activityledger.core.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * Schema version 1 -> 2 (ADR-040): subject/action tags.
 *
 * Purely additive: it creates four new tables and their indices, adds nullable
 * columns to four existing tables, and adds two indices to canonical_activities.
 * Nothing is dropped, rebuilt or rewritten, so every version-1 row and value
 * survives unchanged; every new column is NULL on migrated rows.
 *
 * canonical_activities.subject_id / action_id are added with plain
 * `ALTER TABLE ... ADD COLUMN ... REFERENCES ...`. SQLite allows a REFERENCES
 * clause on an added column only when its default is NULL, which is the case
 * here, and the resulting foreign keys are reported by PRAGMA foreign_key_list
 * exactly like declared ones, so Room's schema validation against 2.json
 * accepts them; no create-copy-drop-rename rebuild of the table is needed.
 * (The new columns sit after the existing ones, the same order 2.json uses.)
 *
 * The SQL mirrors 2.json's createSql statements; runMigrationsAndValidate in
 * the migration harness checks the result column-for-column against it.
 */
internal val MIGRATION_1_2: Migration = SqlMigration(
    startVersion = 1,
    endVersion = 2,
    statements = listOf(
        // New tag tables (parents first so the pair columns can reference them).
        "CREATE TABLE IF NOT EXISTS `subjects` (`id` TEXT NOT NULL, `display_name` TEXT NOT NULL, " +
            "`normalized_name` TEXT NOT NULL, `status` TEXT NOT NULL, `merged_into_subject_id` TEXT, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`merged_into_subject_id`) REFERENCES `subjects`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_subjects_normalized_name` ON `subjects` (`normalized_name`)",
        "CREATE INDEX IF NOT EXISTS `index_subjects_status` ON `subjects` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_subjects_merged_into_subject_id` ON `subjects` (`merged_into_subject_id`)",
        "CREATE TABLE IF NOT EXISTS `actions` (`id` TEXT NOT NULL, `display_name` TEXT NOT NULL, " +
            "`normalized_name` TEXT NOT NULL, `status` TEXT NOT NULL, `merged_into_action_id` TEXT, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`merged_into_action_id`) REFERENCES `actions`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_actions_normalized_name` ON `actions` (`normalized_name`)",
        "CREATE INDEX IF NOT EXISTS `index_actions_status` ON `actions` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_actions_merged_into_action_id` ON `actions` (`merged_into_action_id`)",
        "CREATE TABLE IF NOT EXISTS `subject_aliases` (`id` TEXT NOT NULL, `subject_id` TEXT NOT NULL, " +
            "`alias_text` TEXT NOT NULL, `normalized_alias` TEXT NOT NULL, `source` TEXT NOT NULL, " +
            "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`subject_id`) REFERENCES " +
            "`subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_subject_aliases_subject_id_normalized_alias` " +
            "ON `subject_aliases` (`subject_id`, `normalized_alias`)",
        "CREATE TABLE IF NOT EXISTS `action_aliases` (`id` TEXT NOT NULL, `action_id` TEXT NOT NULL, " +
            "`alias_text` TEXT NOT NULL, `normalized_alias` TEXT NOT NULL, `source` TEXT NOT NULL, " +
            "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`action_id`) REFERENCES " +
            "`actions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_action_aliases_action_id_normalized_alias` " +
            "ON `action_aliases` (`action_id`, `normalized_alias`)",
        // The subject/action pair on canonical activities (nullable: v3-path rows carry no tags).
        "ALTER TABLE `canonical_activities` ADD COLUMN `subject_id` TEXT DEFAULT NULL " +
            "REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION",
        "ALTER TABLE `canonical_activities` ADD COLUMN `action_id` TEXT DEFAULT NULL " +
            "REFERENCES `actions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_canonical_activities_subject_id_action_id` " +
            "ON `canonical_activities` (`subject_id`, `action_id`)",
        "CREATE INDEX IF NOT EXISTS `index_canonical_activities_action_id` ON `canonical_activities` (`action_id`)",
        // Duration and tag-path extraction columns.
        "ALTER TABLE `activity_occurrences` ADD COLUMN `duration_seconds` INTEGER",
        "ALTER TABLE `interpretations` ADD COLUMN `extracted_subject` TEXT",
        "ALTER TABLE `interpretations` ADD COLUMN `extracted_action` TEXT",
        "ALTER TABLE `interpretations` ADD COLUMN `duration_expression` TEXT",
        "ALTER TABLE `interpretations` ADD COLUMN `resolved_duration_seconds` INTEGER",
        "ALTER TABLE `corrections` ADD COLUMN `previous_duration_seconds` INTEGER",
        "ALTER TABLE `corrections` ADD COLUMN `new_duration_seconds` INTEGER",
    ),
)

/**
 * A migration that is a fixed list of SQL statements, run in order. Both entry
 * points are implemented: the SupportSQLite one is what the production factory
 * uses, the driver one is what the driver-based MigrationTestHelper uses.
 */
private class SqlMigration(
    startVersion: Int,
    endVersion: Int,
    private val statements: List<String>,
) : Migration(startVersion, endVersion) {

    override fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach { db.execSQL(it) }
    }

    override fun migrate(connection: SQLiteConnection) {
        statements.forEach { connection.execSQL(it) }
    }
}
