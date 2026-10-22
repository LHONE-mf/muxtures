package com.slurrycalc.calc

import com.slurrycalc.data.AUDIT_GENESIS
import com.slurrycalc.data.Audit
import com.slurrycalc.data.AuditRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B1: цепочка хэшей audit_log — чистые JVM-тесты verifyChain на синтетических записях.
 * (Инструментальная проверка на живой БД — MigrationRoomTest/Robolectric, отдельный файл.)
 */
class VerifyChainTest {

    /** Построить корректную цепочку из n записей (как это делает Audit.record) */
    private fun buildChain(n: Int): List<AuditRow> {
        val rows = mutableListOf<AuditRow>()
        var prev = AUDIT_GENESIS
        repeat(n) { i ->
            val ts = 1_700_000_000_000L + i * 1000
            val rowHash = Audit.computeRowHash(prev, ts, "tester", "EVT_$i", "детали $i")
            rows += AuditRow(id = (i + 1).toLong(), ts = ts, actor = "tester",
                action = "EVT_$i", details = "детали $i", prevHash = prev, rowHash = rowHash)
            prev = rowHash
        }
        return rows
    }

    @Test fun `нетронутая цепочка проходит проверку`() {
        assertNull(Audit.verifyChain(buildChain(10)))
        assertNull(Audit.verifyChain(emptyList()))       // пустая — не нарушение
        assertNull(Audit.verifyChain(listOf(buildChain(1).first())))
    }

    @Test fun `изменённая запись в середине - verifyChain находит подмену содержимого`() {
        val rows = buildChain(6).toMutableList()
        rows[3] = rows[3].copy(details = "ПЕРЕПИСАНО")   // правка существующей записи
        val err = Audit.verifyChain(rows)
        assertNotNull("подмена не обнаружена", err)
        assertEquals(true, err!!.contains("подмена содержимого записи id=4"))
    }

    @Test fun `удалённая запись в середине - verifyChain находит разрыв`() {
        val rows = buildChain(6).toMutableList()
        rows.removeAt(2)                                  // удалили третью запись
        val err = Audit.verifyChain(rows)
        assertNotNull("разрыв не обнаружен", err)
        assertEquals(true, err!!.contains("разрыв цепочки у id=4"))
    }

    @Test fun `подмена genesis (первой записи) обнаруживается`() {
        val rows = buildChain(5).toMutableList()
        rows[0] = rows[0].copy(actor = "evil")            // prevHash остался GENESIS, но rowHash сломан
        assertNotNull(Audit.verifyChain(rows))
        // удаление genesis целиком
        val noGenesis = buildChain(5).drop(1).toMutableList()
        val err = Audit.verifyChain(noGenesis)
        assertNotNull(err)
        assertEquals(true, err!!.contains("genesis"))
    }

    @Test fun `вставка записи в начало (сдиг без пересчёта) ломает цепочку`() {
        val rows = buildChain(4).toMutableList()
        rows.add(0, AuditRow(id = 99, ts = 1L, actor = "x", action = "Y", details = "z",
            prevHash = AUDIT_GENESIS, rowHash = "deadbeef"))
        assertNotNull(Audit.verifyChain(rows))
    }

    @Test fun `computeRowHash детерминирован и чувствителен к каждому полю`() {
        val base = Audit.computeRowHash("p", 1L, "a", "b", "c")
        assertEquals(base, Audit.computeRowHash("p", 1L, "a", "b", "c"))
        assert(base != Audit.computeRowHash("P", 1L, "a", "b", "c"))
        assert(base != Audit.computeRowHash("p", 2L, "a", "b", "c"))
        assert(base != Audit.computeRowHash("p", 1L, "A", "b", "c"))
        assert(base != Audit.computeRowHash("p", 1L, "a", "B", "c"))
        assert(base != Audit.computeRowHash("p", 1L, "a", "b", "C"))
        assertEquals(64, base.length)                     // SHA-256 hex
    }
}
