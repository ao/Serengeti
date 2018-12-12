package gl.ao.addi;

import java.io.*;

public class Globals {

    public static String meta_extention = ".ddbm";
    public static String piece_extention = ".ddbp";
    public static String index_extension = ".ddbi";
    public static String pieces_path = Construct.data_path + "pieces/";
    public static int piece_size = 1*1024*1024;

    /***
     * Convert to Bytes
     * @param object
     * @return
     */
    public static byte[] convertToBytes(Object object) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutput out = new ObjectOutputStream(bos);
            out.writeObject(object);
            return bos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new byte[1];
    }

    /***
     * Convert From Bytes
     * @param bytes
     * @return
     */
    public static Object convertFromBytes(byte[] bytes) {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            ObjectInput in = new ObjectInputStream(bis);
            return in.readObject();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new Object();
    }

}
