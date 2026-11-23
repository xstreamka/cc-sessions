package dev.xstreamka.ccsessions.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ClearHandoffTest {
    private fun run(vararg events: LiveEvent): Map<String, LiveState> {
        val states = HashMap<String, LiveState>()
        for (ev in events.sortedBy { it.ts }) states[ev.sessionId] = LiveReducer.apply(states[ev.sessionId], ev)
        return states
    }

    private fun ev(ts: Long, event: String, sid: String, kind: String? = null, cwd: String? = "/p") =
        LiveEvent(ts, event, sid, cwd = cwd, kind = kind)

    @Test
    fun reducerRemembersClear() {
        val st = run(ev(1, "SessionStart", "old", "startup"), ev(100, "SessionEnd", "old", "clear"), ev(170, "SessionStart", "new", "clear"))
        assertEquals(100L, st["old"]!!.clearedAt)
        assertNull(st["old"]!!.startedByClearAt)
        assertEquals(170L, st["new"]!!.startedByClearAt)
        assertNull(st["new"]!!.clearedAt)
        // Работа в новой сессии метку не стирает
        val later = LiveReducer.apply(st["new"], ev(500, "UserPromptSubmit", "new"))
        assertEquals(170L, later.startedByClearAt)
        // Обычный выход — не clear; повторный запуск (resume) метку снимает
        val exit = run(ev(1, "SessionStart", "a", "startup"), ev(2, "SessionEnd", "a", "prompt_input_exit"))
        assertNull(exit["a"]!!.clearedAt)
        val resumed = LiveReducer.apply(st["old"], ev(900, "SessionStart", "old", "resume"))
        assertNull(resumed.clearedAt)
    }

    @Test
    fun successorAfterClear() {
        val st = run(
            ev(1, "SessionStart", "old", "startup"),
            ev(100, "SessionEnd", "old", "clear"),
            ev(170, "SessionStart", "new", "clear"),
            ev(171, "UserPromptSubmit", "new"),
        )
        assertEquals("new", ClearHandoff.successor("old", st, emptySet()))
        // Уже привязана к другой вкладке — не берём
        assertNull(ClearHandoff.successor("old", st, setOf("new")))
    }

    @Test
    fun noSuccessorWithoutMatchingClear() {
        // Сессия просто закончилась
        val ended = run(ev(100, "SessionEnd", "old", "prompt_input_exit"), ev(170, "SessionStart", "new", "clear"))
        assertNull(ClearHandoff.successor("old", ended, emptySet()))
        // Ещё работает
        val alive = run(ev(100, "UserPromptSubmit", "old"), ev(170, "SessionStart", "new", "clear"))
        assertNull(ClearHandoff.successor("old", alive, emptySet()))
        // clear в другой папке
        val otherCwd = run(ev(100, "SessionEnd", "old", "clear"), ev(170, "SessionStart", "new", "clear", cwd = "/q"))
        assertNull(ClearHandoff.successor("old", otherCwd, emptySet()))
        // Новая сессия началась намного позже — это не наш clear
        val late = run(ev(100, "SessionEnd", "old", "clear"), ev(60_000, "SessionStart", "new", "clear"))
        assertNull(ClearHandoff.successor("old", late, emptySet()))
        // Новая сессия запущена не через clear
        val startup = run(ev(100, "SessionEnd", "old", "clear"), ev(170, "SessionStart", "new", "startup"))
        assertNull(ClearHandoff.successor("old", startup, emptySet()))
    }

    @Test
    fun nearestSuccessorWins() {
        // Два clear почти одновременно в одной папке: каждой старой — ближайшая по времени новая
        val st = run(
            ev(100, "SessionEnd", "a", "clear"),
            ev(150, "SessionStart", "a2", "clear"),
            ev(3000, "SessionEnd", "b", "clear"),
            ev(3050, "SessionStart", "b2", "clear"),
        )
        assertEquals("a2", ClearHandoff.successor("a", st, emptySet()))
        assertEquals("b2", ClearHandoff.successor("b", st, emptySet()))
        // Одинаково близко — неизвестно, чья: не угадываем
        val tie = run(ev(100, "SessionEnd", "a", "clear"), ev(50, "SessionStart", "x", "clear"), ev(150, "SessionStart", "y", "clear"))
        assertNull(ClearHandoff.successor("a", tie, emptySet()))
    }

    @Test
    fun openTabsListReplaced() {
        val list = listOf("a", "old", "b")
        assertEquals(listOf("a", "new", "b"), OpenTabsList.replaced(list, "old", "new")) // место в порядке сохраняется
        assertEquals(listOf("a", "b"), OpenTabsList.replaced(list, "old", "b")) // новая уже есть — без дубля
        assertEquals(listOf("a", "old", "b", "new"), OpenTabsList.replaced(list, "zzz", "new")) // старой не было — как открытие
        assertSame(list, OpenTabsList.replaced(list, "old", "old"))
    }
}
