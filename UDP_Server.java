import java.net.*;
import java.util.*;

public class UDP_Server {

    private int seqNum = 200; // Initial server seq num
    private DatagramSocket socket;
    private Map<Integer, InetSocketAddress> activeSessions = new HashMap<>();
    

    // Constructor
    public UDP_Server(String ipAdd, int port) {
        try {
            InetAddress serverIp = InetAddress.getByName(ipAdd);
            socket = new DatagramSocket(port);
            System.out.println("Server running at IP: " + serverIp + " on port: " + port);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Build packet: TYPE:SEQ:ACK:PAYLOAD
    private String buildPacket(String type, int ack, String payload) {
        return type + ":" + seqNum + ":" + ack + ":" + payload;
    }

    // Handle handshake
    public InetSocketAddress handleHandshake() {
        try {
            // --- Receive SYN
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            InetSocketAddress clientAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
            String message = new String(packet.getData(), 0, packet.getLength());
            System.out.println("Received: " + message);
            String[] p = message.split(":", 4);
            String type = p[0];
            int clientSeq = Integer.parseInt(p[1]);

            if (!type.equals("SYN")) {
                System.out.println("Expected SYN, got: " + message);
                return null;
            }

            // --- Send SYN-ACK (send generated sessionID)
            String sessionID = "1000";
            String synAck = buildPacket("SYN-ACK", clientSeq + 1, sessionID);
            socket.send(new DatagramPacket(synAck.getBytes(), synAck.length(),
                    clientAddress.getAddress(), clientAddress.getPort()));
            System.out.println("Sent: " + synAck);
            seqNum++;

            // --- Receive CONFIRM from client
            buffer = new byte[1024]; // fresh buffer
            DatagramPacket confirmPacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(confirmPacket);

            String confirmMsg = new String(confirmPacket.getData(), 0, confirmPacket.getLength());
            System.out.println("Received " + confirmMsg);
            String[] c = confirmMsg.split(":", 4);
            int seshID = Integer.parseInt(sessionID);

            // ASSIGN SESSIONID
            if () {
                String ack = buildPacket("SESSIONID_CONFIRMED", Integer.parseInt(c[1]) + 1, "" + sessionID);
                socket.send(new DatagramPacket(ack.getBytes(), ack.length(), clientAddress));
                System.out.println("Sent " + ack);
            }

            return clientAddress;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Main
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        String ipAdd = "127.0.0.1";
        int port = 8000;

        UDP_Server server = new UDP_Server(ipAdd, port);

        System.out.println("Server ready. Waiting for clients...");

        boolean stop = false;
        while (!stop) {
            InetSocketAddress client = server.handleHandshake();
            if (client != null) {
                System.out.println("Handshake complete with client: " + client + "\n");
                stop = true;
            }
        }
    }
}
