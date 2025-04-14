package com.ataiva.serengeti.helpers;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class GlobalsTest {

    static class TestObj {
        static final long serialVersionUID = 1L;
        public String data = "test";
    }

    @Test
    void convertToBytes() {
        TestObj testObj = new TestObj();

        byte[] actual = Globals.convertToBytes(testObj);
        assertNotEquals(720167805, Arrays.hashCode(actual));
    }

    @Test
    void convertFromBytes() {
        TestObj testObj = new TestObj();

        byte[] bytes = Globals.convertToBytes(testObj);
        TestObj actual = (TestObj) Globals.convertFromBytes(bytes);

        assertEquals(testObj.data, actual.data);
//        assertEquals(1, 1);
    }
}