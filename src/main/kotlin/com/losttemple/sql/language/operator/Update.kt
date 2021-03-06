package com.losttemple.sql.language.operator

import com.losttemple.sql.language.generate.CountIdGenerator
import com.losttemple.sql.language.generate.DefaultUpdateConstructor
import com.losttemple.sql.language.generate.EvaluateContext
import com.losttemple.sql.language.operator.sources.SourceColumn
import com.losttemple.sql.language.types.SqlType
import java.sql.Connection


class DbUpdateEnvironment(table: String, val condition: SqlType<Boolean>?) {
    private val update = DefaultUpdateConstructor(table)

    init {
        condition?.push(update.where)
    }

    operator fun <T> SourceColumn<T>.invoke(value: T) {
        val constructor = update.addValue(name)
        val parameter = generateParameter(value)
        parameter.setParam(constructor)
    }

    operator fun <T> SourceColumn<T>.invoke(value: SqlType<T>) {
        val constructor = update.addValue(name)
        value.push(constructor)
    }

    fun execute(machine: SqlDialect, connection: Connection): Int {
        val context = EvaluateContext(machine, CountIdGenerator(), mapOf(), mapOf())
        update.root(context)
        println(machine.sql.describe())
        return machine.sql.prepare(connection).use {
            statement -> statement.executeUpdate()
        }
    }
}