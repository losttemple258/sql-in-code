package com.losttemple.sql.language.dialects

import com.losttemple.sql.language.operator.*
import com.losttemple.sql.language.types.SqlType
import java.sql.Connection
import java.math.BigInteger
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.time.Duration
import java.util.*
import kotlin.collections.ArrayList

class MySqlDialect: SqlDialect {
    private val stack = Stack<JdbcSqlSegment>()

    private fun pop(): JdbcSqlSegment {
        return stack.pop()
    }

    private fun push(sql: String, parameters: List<JdbcSqlParameter>) {
        val segment = JdbcSqlSegment(sql, parameters)
        stack.push(segment)
    }

    private fun push(sql: String) {
        val segment = JdbcSqlSegment(sql, emptyList())
        stack.push(segment)
    }

    private fun push(sql: String, parameter: JdbcSqlParameter) {
        val segment = JdbcSqlSegment(sql, listOf(parameter))
        stack.push(segment)
    }

    private fun push(statement: JdbcSqlSegment) {
        stack.push(statement)
    }

    private fun push(sql: String, vararg segments: JdbcSqlSegment) {
        val count = segments.sumBy { it.parameters.size }
        val result = ArrayList<JdbcSqlParameter>(count)
        for (segment in segments) {
            result.addAll(segment.parameters)
        }
        push(sql, result)
    }

    override fun table(name: String) {
        push("`${name}`")
    }

    override fun where() {
        val condition = pop()
        val fromSource = pop()
        push("$fromSource WHERE $condition", fromSource, condition)
    }

    override fun having() {
        val condition = pop()
        val fromSource = pop()
        push("$fromSource HAVING $condition", fromSource, condition)
    }

    override fun column(name: String) {
        push("`$name`")
    }

    override fun column(table: String, name: String) {
        push("`$table`.`$name`")
    }

    override fun constance(value: BigInteger?) {
        push("?", JdbcIntParameter(value))
    }

    override fun constance(value: String?) {
        push("?", JdbcStringParameter(value))
    }

    override fun constance(value: java.sql.Date?) {
        push("?", JdbcDateParameter(value))
    }

    override fun constance(value: Time?) {
        push("?", JdbcTimeParameter(value))
    }

    override fun constance(value: Timestamp?) {
        push("?", JdbcTimestampParameter(value))
    }

    override fun constance(value: Boolean?) {
        push("?", JdbcBoolAsIntParameter(value))
    }

    override fun constance(value: Double?) {
        push("?", JdbcDoubleParameter(value))
    }

    override fun columnList() {
    }

    override fun addToList() {
        val newColumn = pop()
        val columnList = pop()
        push("$columnList, $newColumn", columnList, newColumn)
    }

    override fun select() {
        val columns = pop()
        val sourceSet = pop()
        if (stack.size > 0) {
            push("(SELECT $columns FROM $sourceSet)", columns, sourceSet)
        }
        else {
            push("SELECT $columns FROM $sourceSet", columns, sourceSet)
        }
    }

    override fun rename(newName: String) {
        val value = pop()
        if (value.sql.startsWith("SELECT ")) {
            push("($value) AS $newName", value)
        }
        else {
            push("$value AS $newName", value)
        }
    }

    override fun and() {
        val right = pop()
        val left = pop()
        push("($left) AND ($right)", left, right)
    }

    override fun or() {
        val right = pop()
        val left = pop()
        push("($left) OR ($right)", left, right)
    }

    override fun eq() {
        val right = pop()
        val left = pop()
        push("$left = $right", left, right)
    }

    override fun greater() {
        val right = pop()
        val left = pop()
        push("$left > $right", left, right)
    }

    override fun add() {
        val right = pop()
        val left = pop()
        push("$left + $right", left, right)
    }

    override fun subtraction() {
        val right = pop()
        val left = pop()
        push("$left - $right", left, right)
    }

    private interface DurationPart {
        fun getPart(duration: Duration): Long
        fun fromPart(count: Long): Duration
        val name: String
    }

    private fun DurationPart.isWhole(duration: Duration): Boolean {
        val count = getPart(duration)
        val asWhole = fromPart(count)
        return duration == asWhole
    }

    companion object {
        private val defaultPart = object: DurationPart {
            override fun getPart(duration: Duration): Long {
                return duration.toMillis()
            }
            override fun fromPart(count: Long): Duration {
                return Duration.ofMillis(count)
            }
            override val name: String
                get() = "MICROSECOND"

        }
        private val durationParts = listOf<DurationPart>(
            object: DurationPart {
                override fun getPart(duration: Duration): Long {
                    return duration.toDays()
                }
                override fun fromPart(count: Long): Duration {
                    return Duration.ofDays(count)
                }
                override val name: String
                    get() = "DAY"

            },
            object: DurationPart {
                override fun getPart(duration: Duration): Long {
                    return duration.toHours()
                }
                override fun fromPart(count: Long): Duration {
                    return Duration.ofHours(count)
                }
                override val name: String
                    get() = "HOUR"
            },
            object: DurationPart {
                override fun getPart(duration: Duration): Long {
                    return duration.toMinutes()
                }
                override fun fromPart(count: Long): Duration {
                    return Duration.ofMinutes(count)
                }
                override val name: String
                    get() = "MINUTE"
            },
            object: DurationPart {
                override fun getPart(duration: Duration): Long {
                    return duration.seconds
                }
                override fun fromPart(count: Long): Duration {
                    return Duration.ofSeconds(count)
                }
                override val name: String
                    get() = "SECOND"
            }
        )
    }

    private fun removeNanoSeconds(period: Duration): Duration {
        val milliseconds = period.toMillis()
        return Duration.ofMillis(milliseconds)
    }

    override fun addPeriod(period: Duration) {
        val base = pop()
        val duration = removeNanoSeconds(period)
        if (duration == Duration.ZERO) {
            return
        }
        if (duration < Duration.ZERO) {
            return subPeriod(duration.negated())
        }
        val firstPart = durationParts.firstOrNull{
            it.getPart(duration) > 0
        }?: defaultPart
        if (firstPart.isWhole(duration)) {
            push("DATE_ADD($base, INTERVAL ${firstPart.getPart(duration)} ${firstPart.name})", base)
        }
        else {
            val wholePart = durationParts.firstOrNull {
                it.isWhole(duration)
            } ?: defaultPart
            val firstPartValue = firstPart.getPart(duration)
            val remainder = duration.minus(firstPart.fromPart(firstPartValue))
            push("DATE_ADD($base, INTERVAL '${firstPartValue} ${wholePart.getPart(remainder)}' ${firstPart.name}_${wholePart.name})", base)
        }
    }

    override fun subPeriod(period: Duration) {
        val base = pop()
        val duration = removeNanoSeconds(period)
        if (duration == Duration.ZERO) {
            return
        }
        if (duration < Duration.ZERO) {
            return subPeriod(duration.negated())
        }
        val firstPart = durationParts.firstOrNull{
            it.getPart(duration) > 0
        }?: defaultPart
        if (firstPart.isWhole(duration)) {
            push("DATE_SUB($base, INTERVAL ${firstPart.getPart(duration)} ${firstPart.name})", base)
        }
        else {
            val wholePart = durationParts.firstOrNull {
                it.isWhole(duration)
            } ?: defaultPart
            val firstPartValue = firstPart.getPart(duration)
            val remainder = duration.minus(firstPart.fromPart(firstPartValue))
            push("DATE_SUB($base, INTERVAL '${firstPartValue} ${wholePart.getPart(remainder)}' ${firstPart.name}_${wholePart.name})", base)
        }
    }

    override fun leftJoin() {
        val condition = pop()
        val right = pop()
        val left = pop()
        push("($left LEFT JOIN $right ON $condition)", left, right, condition)
    }

    override fun rightJoin() {
        val condition = pop()
        val right = pop()
        val left = pop()
        push("($left RIGHT JOIN $right ON $condition)", left, right, condition)
    }

    override fun innerJoin() {
        val condition = pop()
        val right = pop()
        val left = pop()
        push("($left INNER JOIN $right ON $condition)", left, right, condition)
    }

    override fun outerJoin() {
        val condition = pop()
        val right = pop()
        val left = pop()
        push("($left FULL OUTER JOIN $right ON $condition)", left, right, condition)
    }

    override fun max() {
        val value = pop()
        push("MAX($value)", value)
    }

    override fun min() {
        val value = pop()
        push("MIN($value)", value)
    }

    override fun sum() {
        val value = pop()
        push("SUM($value)", value)
    }

    override fun count() {
        val value = pop()
        push("COUNT($value)", value)
    }

    override fun now() {
        push("NOW()")
    }

    override fun insert(name: String) {
        val values = pop()
        push("INSERT INTO `$name` VALUES ($values)", values)
    }

    override fun insertWithColumns(name: String) {
        val values = pop()
        val columns = pop()
        push("INSERT INTO `$name` ($columns) VALUES ($values)", values)
    }

    override fun assign() {
        val right = pop()
        val left = pop()
        push("$left = $right", left, right)
    }

    override fun assignList() {
    }

    override fun addAssign() {
        val newAssign = pop()
        val assignList = pop()
        push("$assignList, $newAssign", assignList, newAssign)
    }

    override fun updateWithFilter() {
        val filter = pop()
        val assigns = pop()
        val table = pop()
        push("UPDATE $table SET $assigns WHERE $filter", table, assigns, filter)
    }

    override fun updateAll() {
        val assigns = pop()
        val table = pop()
        push("UPDATE $table SET $assigns", table, assigns)
    }

    override fun delete() {
        val filter = pop()
        val table = pop()
        push("DELETE FROM $table WHERE $filter", table, filter)
    }

    override fun deleteAll(name: String) {
        push("DELETE FROM $name")
    }

    override fun order() {
        val key = pop()
        val source = pop()
        push("$source ORDER BY $key", source, key)
    }

    override fun descKey() {
        val key = pop()
        push("$key DESC", key)
    }

    override fun limitWithOffset(count: Int, offset: Int) {
        val source = pop()
        push("$source LIMIT $count OFFSET $offset", source)
    }

    override fun limit(count: Int) {
        val source = pop()
        push("$source LIMIT $count", source)
    }

    override fun group() {
        val key = pop()
        val source = pop()
        push("$source GROUP BY $key", source, key)
    }

    override val sql: JdbcSqlSegment
        get() = stack.first()

    override fun byteResult(result: ResultSet, name: String): Byte? {
        val value = result.getByte(name)
        val isNull = result.wasNull()
        if (isNull) {
            return null
        }
        return value
    }

    override fun shortResult(result: ResultSet, name: String): Short? {
        val value = result.getShort(name)
        val isNull = result.wasNull()
        if (isNull) {
            return null
        }
        return value
    }

    override fun intResult(result: ResultSet, name: String): Int? {
        val value = result.getInt(name)
        val isNull = result.wasNull()
        if (isNull) {
            return null
        }
        return value
    }
    override fun longResult(result: ResultSet, name: String): Long? {
        val value = result.getLong(name)
        val isNull = result.wasNull()
        if (isNull) {
            return null
        }
        return value
    }

    override fun bigIntResult(result: ResultSet, name: String): BigInteger? {
        val value = result.getBigDecimal(name).toBigInteger()
        val isNull = result.wasNull()
        if (isNull) {
            return null
        }
        return value
    }

    override fun stringResult(result: ResultSet, name: String): String? {
        val value = result.getString(name)
        val isNull = result.wasNull()
        if (isNull) {
            return null
        }
        return value
    }

    override fun dateResult(result: ResultSet, name: String): Date? {
        val value = result.getTimestamp(name)
        val isNull = result.wasNull()
        if (isNull) {
            return null
        }
        return value
    }

    override fun boolResult(result: ResultSet, name: String): Boolean? {
        val intResult = result.getInt(name)
        val isNull = result.wasNull()
        if (isNull) {
            return null
        }
        return intResult != 0
    }

    override fun doubleResult(result: ResultSet, name: String): Double? {
        val value = result.getDouble(name)
        val isNull = result.wasNull()
        if (isNull) {
            return null
        }
        return value
    }
}

interface EnvironmentRunner<R> {
    fun runWithEnvironment(environment: DialectEnvironment): R
}

interface EnvironmentRunner1<A, R> {
    fun runWithEnvironment(environment: DialectEnvironment, arg: A): R
}

interface EnvironmentRunner2<A1, A2, R> {
    fun runWithEnvironment(environment: DialectEnvironment, arg1: A1, arg2: A2): R
}

interface EnvironmentRunner3<A1, A2, A3, R> {
    fun runWithEnvironment(environment: DialectEnvironment, arg1: A1, arg2: A2, arg3: A3): R
}

interface DialectEnvironment: AutoCloseable {
    fun <T, R> DbResult<T>.select(mapper: QueryResultAccessor.(T)->R): List<R>
    operator fun <T> DbInstance<SqlType<T>>.invoke(): T?
    fun <T, R> DbInstanceResult<T>.select(mapper: QueryResultAccessor.(T)->R): List<R>
    operator fun <T: DbSource> Inserter<T>.invoke()
    operator fun <T: DbSource, R> InserterWithRet<T, R>.invoke(): R
    operator fun Updater.invoke(): Int
    fun <T: DbSource> DbTableDescription<T>.delete()
    fun <T: DbSource> FilteredTableDescriptor<T>.delete()
    operator fun <R> EnvironmentRunner<R>.invoke(): R {
        return runWithEnvironment(this@DialectEnvironment)
    }
    operator fun <A, R> EnvironmentRunner1<A, R>.invoke(arg: A): R {
        return runWithEnvironment(this@DialectEnvironment, arg)
    }
    operator fun <A1, A2, R> EnvironmentRunner2<A1, A2, R>.invoke(arg1: A1, arg2: A2): R {
        return runWithEnvironment(this@DialectEnvironment, arg1, arg2)
    }
    operator fun <A1, A2, A3, R> EnvironmentRunner3<A1, A2, A3, R>.invoke(arg1: A1, arg2: A2, arg3: A3): R {
        return runWithEnvironment(this@DialectEnvironment, arg1, arg2, arg3)
    }
    /*
    fun <T: DbSource> FilteredDbTable<T>.delete()
    fun <T: DbSource> DbTableDescription<T>.delete()
    fun <T: DbSource> delete(creator: ((TableConfigure.()->Unit)-> SetRef)->T)*/
}

interface ConnectionFactory: AutoCloseable {
    fun <T> connect(handler: (Connection) -> T): T
}

open class GenericDialectEnvironment(
        private val connectionCreator: ConnectionFactory,
        private val dialectCreator: () -> SqlDialect): DialectEnvironment {
    override fun <T, R> DbResult<T>.select(mapper: QueryResultAccessor.(T)->R): List<R> {
        val dialect = dialectCreator()
        return connectionCreator.connect { select(dialect, it, mapper) }
    }

    override operator fun <T> DbInstance<SqlType<T>>.invoke(): T? {
        val dialect = dialectCreator()
        return connectionCreator.connect { invoke(dialect, it) }
    }

    override fun <T, R> DbInstanceResult<T>.select(mapper: QueryResultAccessor.(T) -> R): List<R> {
        val dialect = dialectCreator()
        return connectionCreator.connect { select(dialect, it, mapper) }
    }

    override fun <T : DbSource> Inserter<T>.invoke() {
        val dialect = dialectCreator()
        return connectionCreator.connect { run(dialect, it) }
    }

    override fun <T : DbSource, R> InserterWithRet<T, R>.invoke(): R {
        val dialect = dialectCreator()
        return connectionCreator.connect { run(dialect, it) }
    }

    override fun Updater.invoke(): Int {
        val dialect = dialectCreator()
        return connectionCreator.connect { run(dialect, it) }
    }

    override fun <T : DbSource> DbTableDescription<T>.delete() {
        val dialect = dialectCreator()
        return connectionCreator.connect { delete(dialect, it) }
    }

    override fun <T : DbSource> FilteredTableDescriptor<T>.delete() {
        val dialect = dialectCreator()
        return connectionCreator.connect { delete(dialect, it) }
    }

    override fun close() {
        connectionCreator.close()
    }

    fun runSql(sql: String) {
        connectionCreator.connect { connection ->
            connection.prepareStatement(sql).use {
                it.execute()
            }
        }
    }
}

class MonopolyConnectionFactory(private val connection: Connection): ConnectionFactory {
    override fun <T> connect(handler: (Connection) -> T): T {
        return handler(connection)
    }

    override fun close() {
        connection.close()
    }
}

class CreateConnectionFactory private constructor(private val connection: Connection):
        ConnectionFactory {
    constructor(driver: String, connectionString: String):
            this(connectTo(driver, connectionString))

    constructor(driver: String, connectionString: String, user: String, password: String):
            this(connectTo(driver, connectionString, user, password))

    override fun <T> connect(handler: (Connection) -> T): T {
        return handler(connection)
    }

    override fun close() {
        connection.close()
    }
}

class MySqlEnvironment(connectionString: String, user: String, password: String):
        GenericDialectEnvironment(
                CreateConnectionFactory("com.mysql.cj.jdbc.Driver", connectionString, user, password),
                {MySqlDialect()})

fun connectMySql(connection: String, user: String, password: String, accessor: MySqlEnvironment.()->Unit) {
    MySqlEnvironment(connection, user, password).use {
        it.accessor()
    }
}