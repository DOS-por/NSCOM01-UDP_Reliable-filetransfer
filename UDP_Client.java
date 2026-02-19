import java.net.*;
import java.util.*;

public class UDP_Client {

    private DatagramSocket socket;

    // Constructor
    public UDP_Client() throws Exception {
        socket = new DatagramSocket();
        System.out.println("UDP Client socket created on local port: " + socket.getLocalPort());
    }

    public void send(String message, InetAddress targetIP, int targetPort) throws Exception {
        byte[] data = message.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, targetIP, targetPort);
        socket.send(packet);
        System.out.println("Sent: " + message);
    }

    public String receive() throws Exception {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return new String(packet.getData(), 0, packet.getLength());
    }

    public void close() {
        socket.close();
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        System.out.println("Enter Server IP:");
        String serverIPStr = sc.nextLine();

        System.out.println("Enter Server Listen Port:");
        int serverPort = Integer.parseInt(sc.nextLine());

        UDP_Client client = new UDP_Client();
        InetAddress serverIP = InetAddress.getByName(serverIPStr);

        //Send SYN
        client.send("SYN", serverIP, serverPort);

        //Receive SYN-ACK (available ports)
        String portsMsg = client.receive();
        System.out.println("Server responded with: " + portsMsg);

        //Send CONFIRM (pick first port)
        String[] parts = portsMsg.split(":");
        String[] ports = parts[1].split(",");
        String chosenPort = ports[0];
        client.send("CONFIRM:" + chosenPort, serverIP, serverPort);

        //Receive PORT_CONFIRMED
        String confirmMsg = client.receive();
        System.out.println("Server confirmation: " + confirmMsg);

        client.close();
        System.out.println("Handshake complete.");
    }
}
