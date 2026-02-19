import java.net.*;
import java.util.*;

public class UDP_Server {

    private DatagramSocket socket;
    private Queue<Integer> openPorts = new LinkedList<>();
    private Map<Integer, InetSocketAddress> activeSessions = new HashMap<>();

    // Constructor
    public UDP_Server(String ipAdd, int port) {
        try {
            InetAddress serverIp = InetAddress.getByName(ipAdd);
            socket = new DatagramSocket(port);
            System.out.println("Server running at IP: " + serverIp + " on port: " + port);

            // Initialize available ports
            openPorts.add(8001);
            openPorts.add(8002);
            openPorts.add(8003);

        } catch (Exception e) {
            e.printStackTrace();
        }
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
            System.out.println("Received from client: " + message + " (" + clientAddress + ")");

            if (!message.equals("SYN")) {
                System.out.println("Expected SYN, got: " + message);
                return null;
            }

            // --- Send SYN-ACK (available ports)
            String ports = String.join(",", openPorts.stream().map(String::valueOf).toArray(String[]::new));
            String synAck = "Available Ports:" + ports;
            socket.send(new DatagramPacket(synAck.getBytes(), synAck.length(),
                    packet.getAddress(), packet.getPort()));
            System.out.println("Sent available ports to client: " + clientAddress);

            // --- Receive CONFIRM from client
            buffer = new byte[1024]; // fresh buffer
            DatagramPacket confirmPacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(confirmPacket);
            String confirmMsg = new String(confirmPacket.getData(), 0, confirmPacket.getLength());
            System.out.println("Received from client: " + confirmMsg);

            if (confirmMsg.startsWith("CONFIRM:")) {
                int chosenPort = Integer.parseInt(confirmMsg.split(":")[1]);

                if (openPorts.contains(chosenPort)) {
                    // Assign port to client and remove from available
                    openPorts.remove(chosenPort);
                    activeSessions.put(chosenPort, clientAddress);

                    // Send PORT_CONFIRMED
                    String ack = "PORT_CONFIRMED:" + chosenPort;
                    socket.send(new DatagramPacket(ack.getBytes(), ack.length(),
                            confirmPacket.getAddress(), confirmPacket.getPort()));

                    System.out.println("Port " + chosenPort + " assigned to " + clientAddress);
                } else {
                    // Port already taken
                    String ack = "PORT_UNAVAILABLE";
                    socket.send(new DatagramPacket(ack.getBytes(), ack.length(),
                            confirmPacket.getAddress(), confirmPacket.getPort()));
                    System.out.println("Client requested unavailable port: " + chosenPort);
                }
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

        System.out.println("Enter Server IP Address:");
        String ipAdd = sc.nextLine();

        System.out.println("Enter Server Listen Port:");
        int port = Integer.parseInt(sc.nextLine());

        UDP_Server server = new UDP_Server(ipAdd, port);

        System.out.println("Server ready. Waiting for clients...");

        while (true) {
            InetSocketAddress client = server.handleHandshake();
            if (client != null) {
                System.out.println("Handshake complete with client: " + client + "\n");
            }
        }
    }
}
