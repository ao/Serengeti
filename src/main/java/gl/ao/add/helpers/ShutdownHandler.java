package gl.ao.add.helpers;

import gl.ao.add.ADD;

public class ShutdownHandler {

    public ShutdownHandler() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Safe Shutdown Initiated..");
                if (ADD.storageScheduler.performPersistToDisk()) {
                    System.out.println("Safe Shutdown Completed Succesfully");
                }
            }
        }));
    }

}
