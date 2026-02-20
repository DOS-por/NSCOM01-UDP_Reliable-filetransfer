import java.net.*;
import java.util.*;

public class UDP_Client {

    private DatagramSocket socket;
    private int seqNum = 100; // Client initial seq num
    private int ackNum = 0;

    // Constructor
    public UDP_Client() throws Exception {
        socket = new DatagramSocket();
        System.out.println("UDP Client socket created on local port: " + socket.getLocalPort());
    }

    private String buildPkt(String type, String payload){
        return type + ":" + seqNum + ":" + ackNum + ":" + payload;
    }

    public void send(String type,String payload, InetAddress targetIP, int targetPort) throws Exception {
        String pkt = buildPkt(type, payload);
        byte[] data = pkt.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, targetIP, targetPort);
        socket.send(packet);
        System.out.println("Sent: " + pkt);
        seqNum++; // Advance client seq num
    }

    public String receive() throws Exception {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        String msg = new String(packet.getData(), 0, packet.getLength());
        System.out.println("Received: " + msg);
        return msg;
    }

    public void close() {
        socket.close();
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        String serverIPStr = "127.0.0.1";
        int serverPort = 8000;

        UDP_Client client = new UDP_Client();
        InetAddress serverIP = InetAddress.getByName(serverIPStr);

        //Send SYN
        client.send("SYN", "", serverIP, serverPort);

        //Receive SYN-ACK (available ports)
        String synAck = client.receive();

        //Extract server initial ACK
        String[] parts1 = synAck.split(":");
        client.ackNum = Integer.parseInt(parts1[1]) + 1; // ack server seq

        //Send CONFIRM
        String[] parts = synAck.split(":", 4);
        String portsPayload = parts[3];
        String[] ports = portsPayload.split(",");
        String chosenPort = ports[0];

        // SEND CONFIRM
        client.send("CONFIRM", chosenPort, serverIP, serverPort);

        // Receive PORT_CONFIRMED
        client.receive();

        client.close();
        System.out.println("Handshake complete.");
    }
}
