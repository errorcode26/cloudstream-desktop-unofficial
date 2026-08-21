package com.lagradost.cloudstream3.desktop.input

import com.lagradost.common.logging.AppLogger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class GamepadButton {
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    BUTTON_A,       // Xbox A / PS Cross (Select / Play)
    BUTTON_B,       // Xbox B / PS Circle (Back / Cancel / Open Dock)
    BUTTON_X,       // Xbox X / PS Square (Search / Quick Action)
    BUTTON_Y,       // Xbox Y / PS Triangle (Provider Menu / Details)
    SHOULDER_LEFT,  // LB / L1 (Prev Provider / Tab)
    SHOULDER_RIGHT, // RB / R1 (Next Provider / Tab)
    TRIGGER_LEFT,   // LT / L2 (Page Up / Prev Season)
    TRIGGER_RIGHT,  // RT / R2 (Page Down / Next Season)
    THUMB_LEFT,     // L3 / Left Stick Click
    THUMB_RIGHT,    // R3 / Right Stick Click (Quick Bookmark)
    START,          // Menu / Start (Context Menu)
    BACK_SELECT,    // View / Select (View All)
    RIGHT_STICK_UP,
    RIGHT_STICK_DOWN,
    RIGHT_STICK_LEFT,
    RIGHT_STICK_RIGHT,
}

/**
 * Native XInput game controller manager.
 * Provides auto-detection, deadzone-filtered analog stick navigation,
 * key-repeat for D-Pad, and dual-mode (Controller vs Keyboard/Mouse) state tracking.
 */
object GamepadManager {
    private const val TAG = "GamepadManager"

    // XInput Button Masks
    private const val XINPUT_GAMEPAD_DPAD_UP = 0x0001
    private const val XINPUT_GAMEPAD_DPAD_DOWN = 0x0002
    private const val XINPUT_GAMEPAD_DPAD_LEFT = 0x0004
    private const val XINPUT_GAMEPAD_DPAD_RIGHT = 0x0008
    private const val XINPUT_GAMEPAD_START = 0x0010
    private const val XINPUT_GAMEPAD_BACK = 0x0020
    private const val XINPUT_GAMEPAD_LEFT_THUMB = 0x0040
    private const val XINPUT_GAMEPAD_RIGHT_THUMB = 0x0080
    private const val XINPUT_GAMEPAD_LEFT_SHOULDER = 0x0100
    private const val XINPUT_GAMEPAD_RIGHT_SHOULDER = 0x0200
    private const val XINPUT_GAMEPAD_A = 0x1000
    private const val XINPUT_GAMEPAD_B = 0x2000
    private const val XINPUT_GAMEPAD_X = 0x4000
    private const val XINPUT_GAMEPAD_Y = 0x8000

    private const val THUMBSTICK_DEADZONE = 14000
    private const val REPEAT_INITIAL_DELAY_MS = 250L
    private const val REPEAT_INTERVAL_MS = 90L

    class XINPUT_GAMEPAD : Structure() {
        @JvmField var wButtons: Short = 0
        @JvmField var bLeftTrigger: Byte = 0
        @JvmField var bRightTrigger: Byte = 0
        @JvmField var sThumbLX: Short = 0
        @JvmField var sThumbLY: Short = 0
        @JvmField var sThumbRX: Short = 0
        @JvmField var sThumbRY: Short = 0

        override fun getFieldOrder(): List<String> = listOf(
            "wButtons", "bLeftTrigger", "bRightTrigger",
            "sThumbLX", "sThumbLY", "sThumbRX", "sThumbRY",
        )
    }

    class XINPUT_STATE : Structure() {
        @JvmField var dwPacketNumber: Int = 0
        @JvmField var Gamepad: XINPUT_GAMEPAD = XINPUT_GAMEPAD()

        override fun getFieldOrder(): List<String> = listOf("dwPacketNumber", "Gamepad")
    }

    interface XInputLib : Library {
        fun XInputGetState(dwUserIndex: Int, pState: XINPUT_STATE): Int
    }

    private var xInput: XInputLib? = null
    private val isInitialized = AtomicBoolean(false)
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Observable States
    private val _isControllerConnected = MutableStateFlow(false)
    val isControllerConnected = _isControllerConnected.asStateFlow()

    private val _isControllerActive = MutableStateFlow(false)
    val isControllerActive = _isControllerActive.asStateFlow()

    private val _events = MutableSharedFlow<GamepadButton>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    // Repeat tracking for directional buttons
    private var lastDirectionalButton: GamepadButton? = null
    private var directionPressStartTime = 0L
    private var lastRepeatTime = 0L

    // Previous frame buttons for single-click triggers
    private var previousButtonsMask: Int = 0
    private var previousLT: Int = 0
    private var previousRT: Int = 0
    private var lastRightStickTime = 0L

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            loadXInput()
            startPolling()
        }
    }

    private fun loadXInput() {
        val dllCandidates = listOf("xinput1_4", "xinput1_3", "xinput9_1_0")
        for (dll in dllCandidates) {
            try {
                xInput = Native.load(dll, XInputLib::class.java)
                AppLogger.i(TAG, "Successfully loaded native Windows XInput DLL: $dll")
                return
            } catch (e: Throwable) {
                AppLogger.d(TAG, "Could not load $dll: ${e.message}")
            }
        }
        AppLogger.w(TAG, "XInput DLLs unavailable on this system.")
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            val state = XINPUT_STATE()
            while (isActive) {
                val lib = xInput
                if (lib != null) {
                    val result = try {
                        lib.XInputGetState(0, state)
                    } catch (_: Throwable) {
                        1167 // ERROR_DEVICE_NOT_CONNECTED
                    }

                    val connected = (result == 0) // ERROR_SUCCESS
                    if (_isControllerConnected.value != connected) {
                        _isControllerConnected.value = connected
                        if (connected) {
                            AppLogger.i(TAG, "Game controller connected (User 0)")
                            _isControllerActive.value = true
                        } else {
                            AppLogger.i(TAG, "Game controller disconnected")
                        }
                    }

                    if (connected) {
                        processInput(state.Gamepad)
                    }
                }
                delay(16) // ~60 Hz polling
            }
        }
    }

    private fun processInput(pad: XINPUT_GAMEPAD) {
        val rawButtons = pad.wButtons.toInt() and 0xFFFF
        val lx = pad.sThumbLX.toInt()
        val ly = pad.sThumbLY.toInt()
        val rx = pad.sThumbRX.toInt()
        val ry = pad.sThumbRY.toInt()
        val lt = pad.bLeftTrigger.toInt() and 0xFF
        val rt = pad.bRightTrigger.toInt() and 0xFF

        // Synthesize directional navigation from D-Pad OR Left Analog Stick
        var currentDirection: GamepadButton? = null
        if ((rawButtons and XINPUT_GAMEPAD_DPAD_UP) != 0 || ly > THUMBSTICK_DEADZONE) {
            currentDirection = GamepadButton.DPAD_UP
        } else if ((rawButtons and XINPUT_GAMEPAD_DPAD_DOWN) != 0 || ly < -THUMBSTICK_DEADZONE) {
            currentDirection = GamepadButton.DPAD_DOWN
        } else if ((rawButtons and XINPUT_GAMEPAD_DPAD_LEFT) != 0 || lx < -THUMBSTICK_DEADZONE) {
            currentDirection = GamepadButton.DPAD_LEFT
        } else if ((rawButtons and XINPUT_GAMEPAD_DPAD_RIGHT) != 0 || lx > THUMBSTICK_DEADZONE) {
            currentDirection = GamepadButton.DPAD_RIGHT
        }

        val now = System.currentTimeMillis()

        // Handle Directional Button Repeats
        if (currentDirection != null) {
            _isControllerActive.value = true
            if (lastDirectionalButton != currentDirection) {
                // First press
                lastDirectionalButton = currentDirection
                directionPressStartTime = now
                lastRepeatTime = now
                _events.tryEmit(currentDirection)
            } else {
                // Button held down -> repeat logic
                val heldDuration = now - directionPressStartTime
                if (heldDuration >= REPEAT_INITIAL_DELAY_MS && (now - lastRepeatTime) >= REPEAT_INTERVAL_MS) {
                    lastRepeatTime = now
                    _events.tryEmit(currentDirection)
                }
            }
        } else {
            lastDirectionalButton = null
        }

        // Handle Right Analog Stick (Fast page scrolling / continuous navigation)
        if (now - lastRightStickTime >= 65L) {
            if (ry > THUMBSTICK_DEADZONE) {
                _isControllerActive.value = true
                _events.tryEmit(GamepadButton.RIGHT_STICK_UP)
                lastRightStickTime = now
            } else if (ry < -THUMBSTICK_DEADZONE) {
                _isControllerActive.value = true
                _events.tryEmit(GamepadButton.RIGHT_STICK_DOWN)
                lastRightStickTime = now
            } else if (rx < -THUMBSTICK_DEADZONE) {
                _isControllerActive.value = true
                _events.tryEmit(GamepadButton.RIGHT_STICK_LEFT)
                lastRightStickTime = now
            } else if (rx > THUMBSTICK_DEADZONE) {
                _isControllerActive.value = true
                _events.tryEmit(GamepadButton.RIGHT_STICK_RIGHT)
                lastRightStickTime = now
            }
        }

        // Handle Triggers (Edge-triggered)
        if (lt > 60 && previousLT <= 60) {
            _isControllerActive.value = true
            _events.tryEmit(GamepadButton.TRIGGER_LEFT)
        }
        if (rt > 60 && previousRT <= 60) {
            _isControllerActive.value = true
            _events.tryEmit(GamepadButton.TRIGGER_RIGHT)
        }
        previousLT = lt
        previousRT = rt

        // Handle Action Buttons (Edge-triggered on button DOWN)
        val justPressed = rawButtons and previousButtonsMask.inv()
        if (justPressed != 0) {
            _isControllerActive.value = true
            if ((justPressed and XINPUT_GAMEPAD_A) != 0) _events.tryEmit(GamepadButton.BUTTON_A)
            if ((justPressed and XINPUT_GAMEPAD_B) != 0) _events.tryEmit(GamepadButton.BUTTON_B)
            if ((justPressed and XINPUT_GAMEPAD_X) != 0) _events.tryEmit(GamepadButton.BUTTON_X)
            if ((justPressed and XINPUT_GAMEPAD_Y) != 0) _events.tryEmit(GamepadButton.BUTTON_Y)
            if ((justPressed and XINPUT_GAMEPAD_LEFT_SHOULDER) != 0) _events.tryEmit(GamepadButton.SHOULDER_LEFT)
            if ((justPressed and XINPUT_GAMEPAD_RIGHT_SHOULDER) != 0) _events.tryEmit(GamepadButton.SHOULDER_RIGHT)
            if ((justPressed and XINPUT_GAMEPAD_LEFT_THUMB) != 0) _events.tryEmit(GamepadButton.THUMB_LEFT)
            if ((justPressed and XINPUT_GAMEPAD_RIGHT_THUMB) != 0) _events.tryEmit(GamepadButton.THUMB_RIGHT)
            if ((justPressed and XINPUT_GAMEPAD_START) != 0) _events.tryEmit(GamepadButton.START)
            if ((justPressed and XINPUT_GAMEPAD_BACK) != 0) _events.tryEmit(GamepadButton.BACK_SELECT)
        }

        previousButtonsMask = rawButtons
    }

    /**
     * Notify manager that keyboard or mouse was used to hide controller overlays.
     */
    fun notifyKeyboardOrMouseUsed() {
        if (_isControllerActive.value) {
            _isControllerActive.value = false
        }
    }

    /**
     * Simulate a button press (for testing or keyboard accessibility fallback).
     */
    fun simulateButtonPress(button: GamepadButton) {
        _isControllerActive.value = true
        _events.tryEmit(button)
    }
}
