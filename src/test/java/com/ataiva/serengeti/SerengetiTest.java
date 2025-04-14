package com.ataiva.serengeti;

import com.ataiva.serengeti.storage.Storage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SerengetiTest {

    @Test
    void main() {

        Serengeti.main(new String[] {});

        assertEquals(Serengeti.storage.getClass(), Storage.class);
    }
}