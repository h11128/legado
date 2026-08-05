package io.legado.app.lib.cronet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeoutException

class CronetHardStopTest {

    @Test
    fun timeoutExceptionIsHardStop() {
        assertTrue(CronetHardStop.isHardStop(TimeoutException("x")))
    }

    @Test
    fun cronetTimeoutMessageIsHardStop() {
        assertTrue(CronetHardStop.isHardStop(IOException("Cronet timeout after wait 60000ms")))
        assertTrue(CronetHardStop.isHardStop(IOException("net::ERR_CONNECTION_TIMED_OUT")))
        assertTrue(CronetHardStop.isHardStop(IOException("Cronet interrupted")))
        assertTrue(CronetHardStop.isHardStop(IOException("Cronet Request Canceled")))
        assertFalse(CronetHardStop.isHardStop(IOException("operation Cancelled by user policy")))
    }

    @Test
    fun certErrorIsNotHardStop() {
        assertFalse(CronetHardStop.isHardStop(IOException("ERR_CERT_DATE_INVALID")))
    }
}
