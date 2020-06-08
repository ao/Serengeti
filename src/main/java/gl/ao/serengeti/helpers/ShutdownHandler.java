package gl.ao.serengeti.helpers;

public class ShutdownHandler {

    public ShutdownHandler() {

        /**
         * This does not appear to work correctly on Windows 10 during testing
         * There are bugs online stating the same discovery regarding Windows
         * e.g. https://netbeans.org/bugzilla/show_bug.cgi?id=22641
         * e.g. https://bugs.openjdk.java.net/browse/JDK-7068835
         */

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Safe Shutdown Initiated..");
                if (gl.ao.serengeti.Serengeti.storageScheduler.performPersistToDisk()) {
                    System.out.println("Safe Shutdown Completed Succesfully");
                }
            }
        }));
    }

}
