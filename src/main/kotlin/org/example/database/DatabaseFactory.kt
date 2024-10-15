package org.example.database

import kotlinx.coroutines.Dispatchers
import org.example.models.Content
import org.example.models.ContentStorage
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {
        val driverClassName = "org.postgresql.Driver"
        val jdbcURL = "jdbc:postgresql://localhost:5432/moderation_db"
        val user = "moderator"
        val password = "moderator1"

        Database.connect(jdbcURL, driverClassName, user, password)
        createTables()
    }

    private fun createTables() {
        transaction {
            SchemaUtils.create(Content, ContentStorage)
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
