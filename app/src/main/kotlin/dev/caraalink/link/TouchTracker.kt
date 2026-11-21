package dev.caraalink.link

import android.view.MotionEvent

/** One finger on the car screen, in car-screen pixels. [id] is a small Android pointer id, not the car's slot. */
data class TouchPointer(val id: Int, val x: Int, val y: Int)

/**
 * Every finger currently on the car screen, and which one changed.
 * [action] is a masked MotionEvent action (DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP);
 * [actionIndex] is the changed finger's index in [pointers].
 */
data class TouchGesture(val action: Int, val actionIndex: Int, val pointers: List<TouchPointer>)

/**
 * Turns the car's screen events, one finger per message, into whole-screen
 * gestures so pinch and two-finger moves reach Android Auto.
 *
 * The car tags each event with a slot (`ECTouchEventData.slot` in the
 * net.easyconn decompile); each slot is one finger. Whether a given head unit
 * really sends more than slot 0 is not confirmed from a capture, so a single
 * finger keeps working whatever its events carry in the slot field.
 */
class TouchTracker {
    private class Finger(val id: Int, var x: Int, var y: Int)

    /** Car slot -> finger, in the order the fingers went down. */
    private val fingers = LinkedHashMap<Int, Finger>()

    /** Forgets every finger, e.g. when the car's screen restarts mid-touch. */
    @Synchronized
    fun reset() = fingers.clear()

    /** [action] is MotionEvent.ACTION_DOWN, ACTION_MOVE or ACTION_UP for one finger. Null means nothing to deliver. */
    @Synchronized
    fun onEvent(action: Int, slot: Int, x: Int, y: Int): TouchGesture? = when (action) {
        MotionEvent.ACTION_DOWN -> {
            val known = fingers[slot]
            if (known != null) {
                // A second DOWN for a finger already down: its UP was lost, treat it as a move.
                known.x = x; known.y = y
                gesture(MotionEvent.ACTION_MOVE, slot)
            } else {
                fingers[slot] = Finger(freeId(), x, y)
                gesture(if (fingers.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, slot)
            }
        }
        MotionEvent.ACTION_MOVE -> resolve(slot)?.let { key ->
            fingers.getValue(key).apply { this.x = x; this.y = y }
            gesture(MotionEvent.ACTION_MOVE, key)
        }
        MotionEvent.ACTION_UP -> resolve(slot)?.let { key ->
            fingers.getValue(key).apply { this.x = x; this.y = y }
            val last = fingers.size == 1
            gesture(if (last) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, key)
                .also { fingers.remove(key) }
        }
        else -> null
    }

    /** The finger an event belongs to; with only one finger down, any slot means that finger. */
    private fun resolve(slot: Int): Int? = when {
        slot in fingers -> slot
        fingers.size == 1 -> fingers.keys.first()
        else -> null
    }

    /** Lowest pointer id not in use; Android pointer ids must stay small. */
    private fun freeId(): Int {
        val used = fingers.values.mapTo(HashSet()) { it.id }
        return generateSequence(0) { it + 1 }.first { it !in used }
    }

    private fun gesture(action: Int, changedSlot: Int): TouchGesture {
        val keys = fingers.keys.toList()
        return TouchGesture(
            action = action,
            actionIndex = keys.indexOf(changedSlot),
            pointers = keys.map { fingers.getValue(it).let { f -> TouchPointer(f.id, f.x, f.y) } },
        )
    }
}
