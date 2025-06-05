package com.ataiva.serengeti.unit.index;

import static org.junit.Assert.*;

import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.ataiva.serengeti.index.BTreeIndex;

public class BTreeIndexTest {
    
    private BTreeIndex index;
    
    @Before
    public void setUp() {
        index = new BTreeIndex("testdb", "testtable", "testcolumn");
    }
    
    @Test
    public void testInsertAndFind() {
        // Insert some values
        index.insert("value1", "row1");
        index.insert("value2", "row2");
        index.insert("value3", "row3");
        
        // Find values
        Set<String> result1 = index.find("value1");
        Set<String> result2 = index.find("value2");
        Set<String> result3 = index.find("value3");
        Set<String> result4 = index.find("value4");
        
        // Verify results
        assertEquals(1, result1.size());
        assertTrue(result1.contains("row1"));
        
        assertEquals(1, result2.size());
        assertTrue(result2.contains("row2"));
        
        assertEquals(1, result3.size());
        assertTrue(result3.contains("row3"));
        
        assertEquals(0, result4.size());
    }
    
    @Test
    public void testInsertDuplicateValues() {
        // Insert duplicate values
        index.insert("value1", "row1");
        index.insert("value1", "row2");
        index.insert("value1", "row3");
        
        // Find values
        Set<String> result = index.find("value1");
        
        // Verify results
        assertEquals(3, result.size());
        assertTrue(result.contains("row1"));
        assertTrue(result.contains("row2"));
        assertTrue(result.contains("row3"));
    }
    
    @Test
    public void testRemove() {
        // Insert values
        index.insert("value1", "row1");
        index.insert("value1", "row2");
        index.insert("value2", "row3");
        
        // Remove values
        boolean removed1 = index.remove("value1", "row1");
        boolean removed2 = index.remove("value2", "row3");
        boolean removed3 = index.remove("value3", "row4");
        
        // Verify results
        assertTrue(removed1);
        assertTrue(removed2);
        assertFalse(removed3);
        
        Set<String> result1 = index.find("value1");
        Set<String> result2 = index.find("value2");
        
        assertEquals(1, result1.size());
        assertTrue(result1.contains("row2"));
        
        assertEquals(0, result2.size());
    }
    
    @Test
    public void testFindRange() {
        // Insert numeric values
        index.insert(10, "row1");
        index.insert(20, "row2");
        index.insert(30, "row3");
        index.insert(40, "row4");
        index.insert(50, "row5");
        
        // Find range
        Set<String> result1 = index.findRange(15, 35);
        Set<String> result2 = index.findRange(10, 50);
        Set<String> result3 = index.findRange(60, 70);
        
        // Verify results
        assertEquals(2, result1.size());
        assertTrue(result1.contains("row2"));
        assertTrue(result1.contains("row3"));
        
        assertEquals(5, result2.size());
        assertTrue(result2.contains("row1"));
        assertTrue(result2.contains("row2"));
        assertTrue(result2.contains("row3"));
        assertTrue(result2.contains("row4"));
        assertTrue(result2.contains("row5"));
        
        assertEquals(0, result3.size());
    }
    
    @Test
    public void testRebuild() {
        // Insert values
        index.insert("value1", "row1");
        index.insert("value2", "row2");
        index.insert("value3", "row3");
        
        // Create a map of row data
        java.util.Map<String, String> tableData = new java.util.HashMap<>();
        tableData.put("row4", "{\"testcolumn\":\"value4\"}");
        tableData.put("row5", "{\"testcolumn\":\"value5\"}");
        tableData.put("row6", "{\"testcolumn\":\"value6\"}");
        
        // Rebuild the index
        index.rebuild(tableData);
        
        // Verify old values are gone
        Set<String> result1 = index.find("value1");
        assertEquals(0, result1.size());
        
        // Verify new values are present
        Set<String> result4 = index.find("value4");
        Set<String> result5 = index.find("value5");
        Set<String> result6 = index.find("value6");
        
        assertEquals(1, result4.size());
        assertTrue(result4.contains("row4"));
        
        assertEquals(1, result5.size());
        assertTrue(result5.contains("row5"));
        
        assertEquals(1, result6.size());
        assertTrue(result6.contains("row6"));
    }
}