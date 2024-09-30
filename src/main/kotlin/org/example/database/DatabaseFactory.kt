package org.example.database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object DatabaseFactory {
    fun init() {
        val driverClassName = "org.postgresql.Driver"
        val jdbcURL = "jdbc:POSTGRESURL"
        val user = "YOUR USERNAME HERE"
        val password = "YOUR PASSWORD HERE"

        Database.connect(jdbcURL, driverClassName, user, password)
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }
}