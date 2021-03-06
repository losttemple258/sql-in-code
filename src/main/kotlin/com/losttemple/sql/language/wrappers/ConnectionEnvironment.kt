package com.losttemple.sql.language.wrappers

import com.losttemple.sql.language.operator.*
import com.losttemple.sql.language.types.SqlType
import java.sql.Connection
import java.sql.PreparedStatement

open class ConnectionEnvironment(private val connection: Connection): AutoCloseable {
    fun runSql(sql: String, prepare: PreparedStatement.() -> Unit) {
        connection.prepareStatement(sql).use {
            it.prepare()
            it.execute()
        }
    }

    fun runSql(sql: String) {
        connection.prepareStatement(sql).use {
            it.execute()
        }
    }

    fun <T, R> DbResult<T>.select(machine: SqlDialect, mapper: QueryResultAccessor.(T)->R): List<R> {
        return select(machine, connection, mapper)
    }
    operator  fun <T> DbInstance<SqlType<T>>.invoke(machine: SqlDialect): T? {
        return invoke(machine, connection)
    }
    fun <T, R> DbInstanceResult<T>.select(machine: SqlDialect, mapper: QueryResultAccessor.(T)->R): List<R> {
        return select(machine, connection, mapper)
    }

    fun <T: DbSource> Inserter<T>.run(machine: SqlDialect) {
        run(machine, connection)
    }

    fun <T: DbSource, R> InserterWithRet<T, R>.run(machine: SqlDialect) {
        run(machine, connection)
    }

    fun Updater.run(machine: SqlDialect): Int {
        return run(machine, connection)
    }

    fun <T: DbSource> DbTableDescription<T>.delete(machine: SqlDialect) {
        delete(machine, connection)
    }

    fun <T: DbSource> FilteredTableDescriptor<T>.delete(machine: SqlDialect) {
        delete(machine, connection)
    }

    /*

        fun <T: DbSource> FilteredDbTable<T>.delete(machine: SqlDialect) {
            delete(machine, connection)
        }

        fun <T: DbSource> DbTableDescription<T>.delete(machine: SqlDialect) {
            delete(machine, connection)
        }

        fun <T: DbSource> delete(machine: SqlDialect, creator: ((TableConfigure.()->Unit)-> SetRef)->T) {
            delete(machine, creator)
        }
    */
    override fun close() {
        connection.close()
    }
}

fun connect(connection: Connection, accessor: ConnectionEnvironment.()->Unit) {
    val environment = ConnectionEnvironment(connection)
    environment.accessor()
}