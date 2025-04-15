public class SimpleTest {
    public static void main(String[] args) {
        System.out.println("Testing Serengeti package name change");
        try {
            Class<?> clazz = Class.forName("com.ataiva.serengeti.Serengeti");
            System.out.println("Successfully loaded Serengeti class: " + clazz.getName());
        } catch (ClassNotFoundException e) {
            System.out.println("Failed to load Serengeti class: " + e.getMessage());
        }
    }
}