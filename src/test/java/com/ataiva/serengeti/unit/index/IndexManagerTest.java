package com.ataiva.serengeti.unit.index;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.ataiva.serengeti.index.IndexManager;
import com.ataiva.serengeti.schema.TableStorageObject;

@RunWith(MockitoJUnitRunner.class)
public class IndexManagerTest {
    
    private IndexManager indexManager;
    
    @Mock
    private TableStorageObject mockTableStorage;
    
    private Map<String, String> tableData;
    
    @Before
    public void setUp() {
        indexManager = new IndexManager();
        
        // Create sample table data
        tableData = new HashMap<>();
        tableData.put("row1", "{\"id\":\"1\",\"name\":\"John\",\"age\":30}");
        tableData.put("row2", "{\"id\":\"2\",\"name\":\"Jane\",\"age\":25}");
        tableData.put("row3", "{\"id\":\"3\",\"name\":\"Bob\",\"age\":40}");
    }
    
    @Test
    public void testCreateAndDropIndex() {
        // Create an index
        boolean created = indexManager.createIndex("testdb", "testtable", "name", tableData);
        assertTrue(created);
        
        // Verify index exists
        assertTrue(indexManager.hasIndex("testdb", "testtable", "name"));
        
        // Try to create the same index again
        boolean createdAgain = indexManager.createIndex("testdb", "testtable", "name", tableData);
        assertFalse(createdAgain);
        
        // Drop the index
        boolean dropped = indexManager.dropIndex("testdb", "testtable", "name");
        assertTrue(dropped);
        
        // Verify index no longer exists
        assertFalse(indexManager.hasIndex("testdb", "testtable", "name"));
        
        // Try to drop the index again
        boolean droppedAgain = indexManager.dropIndex("testdb", "testtable", "name");
        assertFalse(droppedAgain);
    }
    
    @Test
    public void testFindRows() {
        // Create an index
        indexManager.createIndex("testdb", "testtable", "name", tableData);
        
        // Find rows
        Set<String> johnRows = indexManager.findRows("testdb", "testtable", "name", "John");
        Set<String> janeRows = indexManager.findRows("testdb", "testtable", "name", "Jane");
        Set<String> bobRows = indexManager.findRows("testdb", "testtable", "name", "Bob");
        Set<String> aliceRows = indexManager.findRows("testdb", "testtable", "name", "Alice");
        
        // Verify results
        assertEquals(1, johnRows.size());
        assertTrue(johnRows.contains("row1"));
        
        assertEquals(1, janeRows.size());
        assertTrue(janeRows.contains("row2"));
        
        assertEquals(1, bobRows.size());
        assertTrue(bobRows.contains("row3"));
        
        assertNull(aliceRows); // No index for non-existent column
    }
    
    @Test
    public void testHandleInsert() {
        // Create an index
        indexManager.createIndex("testdb", "testtable", "age", tableData);
        
        // Insert a new row
        JSONObject newRow = new JSONObject();
        newRow.put("id", "4");
        newRow.put("name", "Alice");
        newRow.put("age", 35);
        
        indexManager.handleInsert("testdb", "testtable", "row4", newRow);
        
        // Find the new row
        Set<String> ageRows = indexManager.findRows("testdb", "testtable", "age", 35);
        
        // Verify results
        assertEquals(1, ageRows.size());
        assertTrue(ageRows.contains("row4"));
    }
    
    @Test
    public void testHandleUpdate() {
        // Create an index
        indexManager.createIndex("testdb", "testtable", "age", tableData);
        
        // Get the old JSON
        JSONObject oldJson = new JSONObject(tableData.get("row1"));
        
        // Create the new JSON with updated age
        JSONObject newJson = new JSONObject(oldJson.toString());
        newJson.put("age", 31);
        
        // Update the row
        indexManager.handleUpdate("testdb", "testtable", "row1", oldJson, newJson);
        
        // Find rows with the old and new ages
        Set<String> oldAgeRows = indexManager.findRows("testdb", "testtable", "age", 30);
        Set<String> newAgeRows = indexManager.findRows("testdb", "testtable", "age", 31);
        
        // Verify results
        assertEquals(0, oldAgeRows.size());
        assertEquals(1, newAgeRows.size());
        assertTrue(newAgeRows.contains("row1"));
    }
    
    @Test
    public void testHandleDelete() {
        // Create an index
        indexManager.createIndex("testdb", "testtable", "name", tableData);
        
        // Get the JSON for row1
        JSONObject json = new JSONObject(tableData.get("row1"));
        
        // Delete the row
        indexManager.handleDelete("testdb", "testtable", "row1", json);
        
        // Find rows with the name
        Set<String> nameRows = indexManager.findRows("testdb", "testtable", "name", "John");
        
        // Verify results
        assertEquals(0, nameRows.size());
    }
    
    @Test
    public void testGetTableIndexes() {
        // Create multiple indexes
        indexManager.createIndex("testdb", "testtable", "name", tableData);
        indexManager.createIndex("testdb", "testtable", "age", tableData);
        indexManager.createIndex("testdb", "testtable", "id", tableData);
        
        // Get table indexes
        List<String> indexedColumns = indexManager.getIndexedColumns("testdb", "testtable");
        
        // Verify results
        assertEquals(3, indexedColumns.size());
        assertTrue(indexedColumns.contains("name"));
        assertTrue(indexedColumns.contains("age"));
        assertTrue(indexedColumns.contains("id"));
    }
    
    @Test
    public void testGetAllIndexes() {
        // Create indexes for different tables
        indexManager.createIndex("testdb", "testtable", "name", tableData);
        indexManager.createIndex("testdb", "testtable", "age", tableData);
        indexManager.createIndex("testdb", "othertable", "id", tableData);
        
        // Get all indexes
        List<Map<String, String>> allIndexes = indexManager.getAllIndexes();
        
        // Verify results
        assertEquals(3, allIndexes.size());
        
        // Check that each index has the expected properties
        boolean foundNameIndex = false;
        boolean foundAgeIndex = false;
        boolean foundIdIndex = false;
        
        for (Map<String, String> index : allIndexes) {
            if (index.get("database").equals("testdb") && 
                index.get("table").equals("testtable") && 
                index.get("column").equals("name")) {
                foundNameIndex = true;
            }
            if (index.get("database").equals("testdb") && 
                index.get("table").equals("testtable") && 
                index.get("column").equals("age")) {
                foundAgeIndex = true;
            }
            if (index.get("database").equals("testdb") && 
                index.get("table").equals("othertable") && 
                index.get("column").equals("id")) {
                foundIdIndex = true;
            }
        }
        
        assertTrue(foundNameIndex);
        assertTrue(foundAgeIndex);
        assertTrue(foundIdIndex);
    }
    
    @Test
    public void testRebuildTableIndexes() {
        // Create an index
        indexManager.createIndex("testdb", "testtable", "name", tableData);
        
        // Create new table data
        Map<String, String> newTableData = new HashMap<>();
        newTableData.put("row4", "{\"id\":\"4\",\"name\":\"Alice\",\"age\":35}");
        newTableData.put("row5", "{\"id\":\"5\",\"name\":\"Charlie\",\"age\":45}");
        
        // Rebuild the indexes
        indexManager.rebuildTableIndexes("testdb", "testtable", newTableData);
        
        // Find rows with the old and new names
        Set<String> johnRows = indexManager.findRows("testdb", "testtable", "name", "John");
        Set<String> aliceRows = indexManager.findRows("testdb", "testtable", "name", "Alice");
        Set<String> charlieRows = indexManager.findRows("testdb", "testtable", "name", "Charlie");
        
        // Verify results
        assertEquals(0, johnRows.size());
        assertEquals(1, aliceRows.size());
        assertEquals(1, charlieRows.size());
        assertTrue(aliceRows.contains("row4"));
        assertTrue(charlieRows.contains("row5"));
    }
}