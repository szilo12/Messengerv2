package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Ensure CallManager is reset to clean IDLE state
        CallManager.clearLogs()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testIncomingCallTriggersStateTransitions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callData = CallData(
            callerName = "Test Contact",
            callerSubtitle = "Incoming test video call",
            callerAvatarHexColor = 0xFF4F46E5,
            callType = CallType.VIDEO
        )

        // Trigger call
        CallManager.triggerIncomingCall(context, callData)

        // Verify state is INCOMING
        assertEquals(CallStatus.INCOMING, CallManager.callStatus.value)
        assertEquals(callData, CallManager.currentCall.value)

        // Answer call
        CallManager.answerCall(context)

        // Verify status becomes ONGOING
        assertEquals(CallStatus.ONGOING, CallManager.callStatus.value)

        // End call
        CallManager.endCall(context)

        // Verify it resets to IDLE
        assertEquals(CallStatus.IDLE, CallManager.callStatus.value)
        assertEquals(null, CallManager.currentCall.value)
    }
}
