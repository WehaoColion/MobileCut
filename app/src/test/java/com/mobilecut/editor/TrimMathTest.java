package com.mobilecut.editor;
import org.junit.Test;
import static org.junit.Assert.*;
public class TrimMathTest {
    @Test public void displayTime() {
        assertEquals("00:00",TrimMath.clock(100));
        assertEquals("01:05",TrimMath.clock(65000));
        assertEquals("1:00:01",TrimMath.clock(3601000));
    }
    @Test public void restrict() {
        assertEquals(10L,TrimMath.clamp(15L,0L,10L));
        assertEquals(0L,TrimMath.clamp(-4L,0L,10L));
    }
}
