package com.ataiva.serengeti.storage.lsm;

import java.util.Comparator;

/**
 * Comparator for byte arrays, used to maintain sorted order in the LSM-Tree components.
 * This comparator compares byte arrays lexicographically.
 */
public class ByteArrayComparator implements Comparator<byte[]> {
    
    @Override
    public int compare(byte[] a, byte[] b) {
        int minLength = Math.min(a.length, b.length);
        for (int i = 0; i < minLength; i++) {
            int cmp = Byte.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
    }
}