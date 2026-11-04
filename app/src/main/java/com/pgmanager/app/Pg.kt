package com.pgmanager.app

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.Properties

object Pg {
    fun quote(id: String) = "\"" + id.replace("\"", "\"\"") + "\""
    private fun lit(s: String) = "'" + s.replace("'", "''") + "'"
    private fun qt(schema: String, table: String) = quote(schema) + "." + quote(table)

    fun connect(c: ConnInfo, db: String = c.database): Connection {
        Class.forName("org.postgresql.Driver")
        DriverManager.setLoginTimeout(10)
        val p = Properties()
        p.setProperty("user", c.user)
        p.setProperty("password", c.password)
        p.setProperty("connectTimeout", "10")
        p.setProperty("socketTimeout", "300")
        p.setProperty("ApplicationName", "PgManager")
        if (c.ssl) {
            p.setProperty("ssl", "true")
            p.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory")
        }
        return DriverManager.getConnection("jdbc:postgresql://${c.host}:${c.port}/$db", p)
    }

    // ---------- execução genérica ----------

    fun execute(conn: Connection, sql: String, maxRows: Int = 500): QueryResult {
        val t0 = System.currentTimeMillis()
        conn.createStatement().use { st ->
            st.maxRows = maxRows + 1
            var has = st.execute(sql)
            var last: QueryResult? = null
            var affected = 0L
            while (true) {
                if (has) {
                    st.resultSet.use { rs -> last = readRs(rs, maxRows) }
                } else {
                    val n = st.updateCount
                    if (n == -1) break
                    affected += n
                }
                has = st.moreResults
            }
            val ms = System.currentTimeMillis() - t0
            return last?.copy(millis = ms)
                ?: QueryResult(emptyList(), emptyList(), "Comando executado. Linhas afetadas: $affected", ms)
        }
    }

    private fun readRs(rs: ResultSet, max: Int): QueryResult {
        val md = rs.metaData
        val cols = (1..md.columnCount).map { md.getColumnLabel(it) }
        val rows = ArrayList<List<String?>>()
        var truncated = false
        while (rs.next()) {
            if (rows.size >= max) { truncated = true; break }
            rows.add((1..cols.size).map { rs.getString(it) })
        }
        return QueryResult(cols, rows, null, 0, truncated)
    }

    // ---------- bancos ----------

    fun listDatabases(conn: Connection) = execute(
        conn,
        "SELECT d.datname AS banco, pg_size_pretty(pg_database_size(d.datname)) AS tamanho, " +
            "pg_get_userbyid(d.datdba) AS dono FROM pg_database d " +
            "WHERE NOT d.datistemplate AND d.datallowconn " +
            "AND has_database_privilege(d.datname, 'CONNECT') ORDER BY 1"
    )

    fun createDatabase(conn: Connection, name: String) = execute(conn, "CREATE DATABASE ${quote(name)}")

    fun dropDatabase(conn: Connection, name: String) =
        execute(conn, "DROP DATABASE ${quote(name)} WITH (FORCE)")

    // ---------- tabelas ----------

    fun listTables(conn: Connection) = execute(
        conn,
        "SELECT n.nspname, c.relname, CASE c.relkind WHEN 'r' THEN 'tabela' WHEN 'p' THEN 'particionada' " +
            "WHEN 'v' THEN 'view' WHEN 'm' THEN 'matview' ELSE 'externa' END AS tipo, " +
            "GREATEST(c.reltuples, 0)::bigint AS linhas_estimadas " +
            "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
            "WHERE c.relkind IN ('r','p','v','m','f') " +
            "AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' " +
            "ORDER BY 1, 2",
        5000
    )

    fun tableData(conn: Connection, schema: String, table: String, page: Int, size: Int, withCtid: Boolean): QueryResult {
        val sel = if (withCtid) "ctid::text AS __ctid, *" else "*"
        return execute(conn, "SELECT $sel FROM ${qt(schema, table)} LIMIT $size OFFSET ${page * size}", size)
    }

    fun columns(conn: Connection, schema: String, table: String): QueryResult {
        val reg = lit(qt(schema, table)) + "::regclass"
        return execute(
            conn,
            "SELECT a.attname AS coluna, format_type(a.atttypid, a.atttypmod) AS tipo, " +
                "CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE '' END AS nulo, " +
                "pg_get_expr(d.adbin, d.adrelid) AS padrao " +
                "FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum " +
                "WHERE a.attrelid = $reg AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum",
            1000
        )
    }

    fun indexes(conn: Connection, schema: String, table: String) = execute(
        conn,
        "SELECT indexname AS indice, indexdef AS definicao FROM pg_indexes " +
            "WHERE schemaname = ${lit(schema)} AND tablename = ${lit(table)} ORDER BY 1",
        1000
    )

    fun truncate(conn: Connection, schema: String, table: String) =
        execute(conn, "TRUNCATE TABLE ${qt(schema, table)}")

    fun dropTable(conn: Connection, schema: String, table: String, kind: String): QueryResult {
        val kw = when (kind) {
            "view" -> "VIEW"
            "matview" -> "MATERIALIZED VIEW"
            "externa" -> "FOREIGN TABLE"
            else -> "TABLE"
        }
        return execute(conn, "DROP $kw ${qt(schema, table)}")
    }

    // ---------- linhas (identificadas pelo ctid) ----------

    private fun bind(ps: PreparedStatement, vals: List<String?>) {
        vals.forEachIndexed { i, v ->
            if (v == null) ps.setNull(i + 1, Types.OTHER) else ps.setObject(i + 1, v, Types.OTHER)
        }
    }

    fun insertRow(conn: Connection, schema: String, table: String, values: List<Pair<String, String?>>) {
        if (values.isEmpty()) {
            conn.createStatement().use { it.executeUpdate("INSERT INTO ${qt(schema, table)} DEFAULT VALUES") }
            return
        }
        val cols = values.joinToString(", ") { quote(it.first) }
        val ph = values.joinToString(", ") { "?" }
        conn.prepareStatement("INSERT INTO ${qt(schema, table)} ($cols) VALUES ($ph)").use { ps ->
            bind(ps, values.map { it.second })
            ps.executeUpdate()
        }
    }

    fun updateRow(conn: Connection, schema: String, table: String, ctid: String, changes: List<Pair<String, String?>>) {
        if (changes.isEmpty()) return
        val set = changes.joinToString(", ") { "${quote(it.first)} = ?" }
        conn.prepareStatement("UPDATE ${qt(schema, table)} SET $set WHERE ctid = ?::tid").use { ps ->
            bind(ps, changes.map { it.second } + ctid)
            val n = ps.executeUpdate()
            if (n != 1) error("Nenhuma linha atualizada (os dados mudaram? atualize a lista).")
        }
    }

    fun deleteRow(conn: Connection, schema: String, table: String, ctid: String) {
        conn.prepareStatement("DELETE FROM ${qt(schema, table)} WHERE ctid = ?::tid").use { ps ->
            bind(ps, listOf(ctid))
            val n = ps.executeUpdate()
            if (n != 1) error("Nenhuma linha removida (os dados mudaram? atualize a lista).")
        }
    }

    // ---------- exportação ----------

    fun toCsv(r: QueryResult): String {
        fun esc(s: String?): String =
            if (s == null) "" else if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder()
        sb.append(r.columns.joinToString(",") { esc(it) }).append('\n')
        r.rows.forEach { row -> sb.append(row.joinToString(",") { esc(it) }).append('\n') }
        return sb.toString()
    }
}
