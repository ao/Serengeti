package ms.ao.serengeti.data;

public class DatabaseObjectData {

    private byte[] data;

    public DatabaseObjectData() {}
    public DatabaseObjectData(byte[] data) {
        this.data = data;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
