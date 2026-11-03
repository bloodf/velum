package dev.velum.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMergeTest {
    private val names = listOf("title", "notes", "transcript", "speaker.1")
    private val nodes = listOf("phone", "desktop", "tablet")

    /** A record built from random writes and deletes with random (often colliding) timestamps. */
    private fun randomRecord(rnd: Random): SyncRecord =
        (0 until rnd.nextInt(0, 8)).fold(SyncRecord()) { rec, _ ->
            val hlc = Hlc(rnd.nextLong(0, 5), rnd.nextInt(0, 3), nodes.random(rnd))
            val value = if (rnd.nextInt(4) == 0) null else "v${rnd.nextInt(0, 3)}"
            rec.write(names.random(rnd), value, hlc)
        }

    @Test fun merge_is_commutative_associative_and_idempotent() {
        val rnd = Random(69)
        repeat(500) {
            val a = randomRecord(rnd); val b = randomRecord(rnd); val c = randomRecord(rnd)
            assertEquals(a.merge(b), b.merge(a), "commutative")
            assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)), "associative")
            assertEquals(a, a.merge(a), "idempotent")
            assertEquals(a.merge(b), a.merge(b).merge(b), "re-sending is harmless")
        }
    }

    @Test fun any_exchange_order_converges() {
        val rnd = Random(70)
        repeat(200) {
            val replicas = List(3) { randomRecord(rnd) }
            val forward = replicas.reduce { x, y -> x.merge(y) }
            val shuffled = replicas.shuffled(rnd).reduce { x, y -> x.merge(y) }
            assertEquals(forward, shuffled)
        }
    }

    @Test fun concurrent_edits_to_different_fields_both_survive() {
        val phone = SyncRecord().write("title", "Standup", Hlc(10, 0, "phone"))
        val desktop = SyncRecord().write("notes", "Ship it", Hlc(11, 0, "desktop"))
        val merged = phone.merge(desktop)
        assertEquals("Standup", merged.value("title"))
        assertEquals("Ship it", merged.value("notes"))
    }

    @Test fun concurrent_edits_to_one_field_pick_the_same_winner_on_both_devices() {
        val phone = SyncRecord().write("title", "A", Hlc(10, 0, "phone"))
        val desktop = SyncRecord().write("title", "B", Hlc(10, 0, "desktop"))
        assertEquals(phone.merge(desktop), desktop.merge(phone))
        assertEquals("A", phone.merge(desktop).value("title")) // equal time and counter: the higher node id ("phone") wins
    }

    @Test fun a_newer_delete_beats_an_older_edit_and_a_newer_edit_beats_an_older_delete() {
        val edited = SyncRecord().write("notes", "text", Hlc(10, 0, "phone"))
        val deleted = SyncRecord().delete("notes", Hlc(20, 0, "desktop"))
        assertNull(edited.merge(deleted).value("notes"))
        assertNull(deleted.merge(edited).value("notes"))

        val rewritten = deleted.write("notes", "again", Hlc(30, 0, "phone"))
        assertEquals("again", rewritten.merge(deleted).value("notes"))
        assertEquals("again", deleted.merge(rewritten).value("notes"))
    }

    @Test fun a_tombstone_is_kept_so_a_stale_copy_cannot_resurrect_the_field() {
        val deleted = SyncRecord().delete("notes", Hlc(20, 0, "desktop"))
        assertTrue("notes" in deleted.fields)
        val stale = SyncRecord().write("notes", "old", Hlc(5, 0, "phone"))
        assertNull(deleted.merge(stale).value("notes"))
    }

    @Test fun clock_orders_local_writes_and_sorts_after_anything_received() {
        var wall = 100L
        val clock = HlcClock("phone") { wall }
        val first = clock.send()
        val second = clock.send() // same millisecond: counter breaks the tie
        assertTrue(second > first)

        wall = 50L // clock steps back
        assertTrue(clock.send() > second)

        val fromFuture = Hlc(10_000, 4, "desktop") // other device's clock runs far ahead
        val after = clock.receive(fromFuture)
        assertTrue(after > fromFuture)
        assertTrue(clock.send() > after)
    }

    @Test fun a_write_made_after_receiving_wins_even_with_a_slower_clock() {
        var phoneWall = 1_000L
        val phone = HlcClock("phone") { phoneWall }
        var desktopWall = 9_000L // desktop clock is ahead
        val desktop = HlcClock("desktop") { desktopWall }

        val desktopEdit = SyncRecord().write("title", "from desktop", desktop.send())
        phone.receive(desktopEdit.fields.getValue("title").hlc)
        val phoneEdit = SyncRecord().write("title", "reply from phone", phone.send())
        assertEquals("reply from phone", desktopEdit.merge(phoneEdit).value("title"))
        assertEquals("reply from phone", phoneEdit.merge(desktopEdit).value("title"))
    }
}
