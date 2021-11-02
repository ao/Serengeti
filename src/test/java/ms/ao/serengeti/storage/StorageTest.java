package ms.ao.serengeti.storage;

import ms.ao.serengeti.Serengeti;
import org.junit.jupiter.api.*;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    Serengeti serengeti = new Serengeti();
    Storage storage;

    @BeforeEach
    void beforeEach() {
        storage = new Storage();
        storage.deleteEverything();
    }

    @AfterEach
    void afterEach() {
        storage.deleteEverything();
    }

    @Test
    void test_that_database_created_successfully() {
        String random_db_name = String.format("test_db-%d", new Random().nextInt());

        storage.createDatabase(random_db_name);

        assertTrue(storage.getDatabases().contains(random_db_name));
    }

    @Test
    void test_that_multiple_databases_created_successfully() {
        storage.deleteEverything();

        for (int i = 0; i < 100; i++) {
            String random_db_name = String.format("test_db-%d", new Random().nextInt());

            storage.createDatabase(random_db_name);

            assertTrue(storage.getDatabases().contains(random_db_name));
        }
    }
}
