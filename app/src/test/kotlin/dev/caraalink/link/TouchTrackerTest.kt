package dev.caraalink.link

import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchTrackerTest {

    private val tracker = TouchTracker()

    @Test
    fun `one finger gives down, move, up`() {
        assertEquals(TouchGesture(ACTION_DOWN, 0, listOf(TouchPointer(0, 10, 20))), tracker.onEvent(ACTION_DOWN, 0, 10, 20))
        assertEquals(TouchGesture(ACTION_MOVE, 0, listOf(TouchPointer(0, 15, 25))), tracker.onEvent(ACTION_MOVE, 0, 15, 25))
        assertEquals(TouchGesture(ACTION_UP, 0, listOf(TouchPointer(0, 16, 26))), tracker.onEvent(ACTION_UP, 0, 16, 26))
    }

    @Test
    fun `pinch carries both fingers in every event`() {
        tracker.onEvent(ACTION_DOWN, 0, 100, 100)
        assertEquals(
            TouchGesture(ACTION_POINTER_DOWN, 1, listOf(TouchPointer(0, 100, 100), TouchPointer(1, 200, 200))),
            tracker.onEvent(ACTION_DOWN, 1, 200, 200),
        )
        assertEquals(
            TouchGesture(ACTION_MOVE, 1, listOf(TouchPointer(0, 100, 100), TouchPointer(1, 250, 250))),
            tracker.onEvent(ACTION_MOVE, 1, 250, 250),
        )
        assertEquals(
            TouchGesture(ACTION_POINTER_UP, 0, listOf(TouchPointer(0, 90, 90), TouchPointer(1, 250, 250))),
            tracker.onEvent(ACTION_UP, 0, 90, 90),
        )
        assertEquals(
            TouchGesture(ACTION_UP, 0, listOf(TouchPointer(1, 260, 260))),
            tracker.onEvent(ACTION_UP, 1, 260, 260),
        )
    }

    @Test
    fun `large car slots become small pointer ids, reusing freed ones`() {
        tracker.onEvent(ACTION_DOWN, 7, 1, 1)
        tracker.onEvent(ACTION_DOWN, 9, 2, 2)
        tracker.onEvent(ACTION_UP, 7, 1, 1)
        val third = tracker.onEvent(ACTION_DOWN, 12, 3, 3)!!
        assertEquals(listOf(1, 0), third.pointers.map { it.id })
    }

    @Test
    fun `with one finger down, any slot moves that finger`() {
        tracker.onEvent(ACTION_DOWN, 0, 10, 10)
        assertEquals(TouchGesture(ACTION_MOVE, 0, listOf(TouchPointer(0, 20, 20))), tracker.onEvent(ACTION_MOVE, 5, 20, 20))
        assertEquals(TouchGesture(ACTION_UP, 0, listOf(TouchPointer(0, 30, 30))), tracker.onEvent(ACTION_UP, 3, 30, 30))
    }

    @Test
    fun `unknown finger with two down is ignored`() {
        tracker.onEvent(ACTION_DOWN, 0, 10, 10)
        tracker.onEvent(ACTION_DOWN, 1, 20, 20)
        assertNull(tracker.onEvent(ACTION_MOVE, 4, 30, 30))
        assertNull(tracker.onEvent(ACTION_UP, 4, 30, 30))
    }

    @Test
    fun `move or up with no finger down is ignored`() {
        assertNull(tracker.onEvent(ACTION_MOVE, 0, 1, 1))
        assertNull(tracker.onEvent(ACTION_UP, 0, 1, 1))
    }

    @Test
    fun `repeated down for the same finger is a move`() {
        tracker.onEvent(ACTION_DOWN, 0, 10, 10)
        assertEquals(TouchGesture(ACTION_MOVE, 0, listOf(TouchPointer(0, 12, 12))), tracker.onEvent(ACTION_DOWN, 0, 12, 12))
    }

    @Test
    fun `reset forgets a stuck finger`() {
        tracker.onEvent(ACTION_DOWN, 0, 10, 10)
        tracker.reset()
        assertEquals(ACTION_DOWN, tracker.onEvent(ACTION_DOWN, 1, 5, 5)!!.action)
    }
}
