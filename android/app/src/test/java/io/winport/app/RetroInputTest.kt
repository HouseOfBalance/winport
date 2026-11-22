package io.winport.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetroInputTest {
    @Test
    fun facePositionsUseLibretroPlayStationIds() {
        assertEquals(0, RetroButtons.CROSS)
        assertEquals(8, RetroButtons.CIRCLE)
        assertEquals(1, RetroButtons.SQUARE)
        assertEquals(9, RetroButtons.TRIANGLE)
    }

    @Test
    fun gamepadButtonsMatchVirtualPadPositions() {
        assertEquals(RetroButtons.CROSS, RetroInput.keys[KeyEvent.KEYCODE_BUTTON_A])
        assertEquals(RetroButtons.CIRCLE, RetroInput.keys[KeyEvent.KEYCODE_BUTTON_B])
        assertEquals(RetroButtons.SQUARE, RetroInput.keys[KeyEvent.KEYCODE_BUTTON_X])
        assertEquals(RetroButtons.TRIANGLE, RetroInput.keys[KeyEvent.KEYCODE_BUTTON_Y])
        assertEquals(3, RetroInput.keys[KeyEvent.KEYCODE_BUTTON_START])
        assertEquals(4, RetroInput.keys[KeyEvent.KEYCODE_DPAD_UP])
    }

    @Test
    fun unassignedAndInvalidKeysHaveNoButton() {
        assertNull(RetroInput.keys[KeyEvent.KEYCODE_UNKNOWN])
        assertNull(RetroInput.keys[KeyEvent.KEYCODE_BACK])
        assertNull(RetroInput.keys[-1])
    }
}
