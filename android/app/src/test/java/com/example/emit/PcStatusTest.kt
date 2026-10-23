package com.example.emit
import com.example.emit.ui.PcStatus
import org.junit.Assert.assertEquals
import org.junit.Test
class PcStatusTest { @Test fun status() {
    assertEquals(PcStatus.OFF, PcStatus.of(false, 0)); assertEquals(PcStatus.WAITING, PcStatus.of(true, 0))
    assertEquals(PcStatus.CONNECTED, PcStatus.of(true, 1)) } }
