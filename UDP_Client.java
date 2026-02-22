import javax.xml.crypto.Data;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;

public class UDP_Client {

    private DatagramSocket socket;
    private int seqNum; // Client  seq num
    private int ackNum; // Client's ack num

    // Constructor
    public UDP_Client() throws Exception {
        socket = new DatagramSocket();
        seqNum = new Random().nextInt(10000);
        ackNum = 0;
        System.out.println("UDP Client socket created on local port: " + socket.getLocalPort());
    }

    private String buildPkt(String type, int seq, int ack, byte[] payload) {
        String data = payload != null ? Base64.getEncoder().encodeToString(payload) : "";
        return type + ":" + seq + ":" + ack + ":" + data;
    }

    // Builds packet + sends it to target IP/Port
    public void send(String type, byte[] payload, InetAddress targetIP, int targetPort) throws Exception {
        String pkt = buildPkt(type, seqNum, ackNum, payload);
        socket.send(new DatagramPacket(pkt.getBytes(), pkt.length(), targetIP, targetPort));
        System.out.println("Sent: " + pkt);
        seqNum++; // Increment after sending
    }

    // Receive any packet from server
    // Updates ackNum
    public String receive() throws Exception {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        String msg = new String(packet.getData(), 0, packet.getLength());
        System.out.println("Received: " + msg);

        // Update ackNum from received server SEQ
        String[] parts = msg.split(":", 4);
        int serverSeq = Integer.parseInt(parts[1]);
        ackNum = serverSeq + 1;
        return msg;
    }

    // handles file upload to server, sends file in chunks, waits for ACK b4 sending next chunk
    public void sendFile(String filePath, InetAddress serverIP, int serverPort) throws IOException {
        FileInputStream fileInput = new FileInputStream(filePath);
        byte[] buffer = new byte[3096];
        int bytesRead;

        while ((bytesRead = fileInput.read(buffer)) != -1) {
            byte[] payload = Arrays.copyOf(buffer, bytesRead);
            boolean ackRecv = false;

            while (!ackRecv) {
                // Send DATA packet
                String packet = buildPkt("DATA", seqNum, 0, payload);
                socket.send(new DatagramPacket(packet.getBytes(), packet.length(), serverIP, serverPort));
                System.out.println("Sent DATA seq=" + seqNum + " size=" + bytesRead);

                // Wait for ACK
                try {
                    socket.setSoTimeout(2000);
                    byte[] ackBuf = new byte[2048];
                    DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
                    socket.receive(ackPacket);

                    String ackMsg = new String(ackPacket.getData(), 0, ackPacket.getLength());
                    String[] parts = ackMsg.split(":", 4);
                    String type = parts[0];

                    // ACK number is carried in the payload (Base64-decoded), not parts[3] raw
                    int ackNumRecv = -1;
                    if (type.equals("ACK") && !parts[3].isEmpty()) {
                        byte[] ackPayload = Base64.getDecoder().decode(parts[3]);
                        ackNumRecv = Integer.parseInt(new String(ackPayload));
                    }

                    if (ackNumRecv == seqNum) {
                        ackRecv = true;
                        seqNum++;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout, resending seq=" + seqNum);
                }
            }
        }
        fileInput.close();
        socket.setSoTimeout(0); // Reset timeout

        // Send DATA_END
        String endPacket = buildPkt("DATA_END", seqNum, 0, null);
        socket.send(new DatagramPacket(endPacket.getBytes(), endPacket.length(), serverIP, serverPort));
        System.out.println("File transfer complete. Sent DATA_END.");

    }

    // DOWNload part (REQUEST)
    public void requestFile(String filename, InetAddress serverIP, int serverPort) throws Exception {
        String req = buildPkt("REQ:", seqNum, ackNum, filename.getBytes());
        DatagramPacket packet = new DatagramPacket(req.getBytes(), req.length(), serverIP, serverPort);
        socket.send(packet);
        seqNum++;
        System.out.println("Sent file request: " + req);
    }

    // DOWNLOAD part (recv req)
    public void receiveFile(String savePath) throws Exception {
        FileOutputStream fos = new FileOutputStream(savePath);
        int expectedSeq = -1;
        System.out.println("Receiving file..");

        while(true){
            byte[] buffer = new byte[4096];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            String msg = new String(packet.getData(), 0, packet.getLength());
            System.out.println("Received: " + msg);

            String[] p = msg.split(":", 4);
            String type = p[0];
            int seq = Integer.parseInt(p[1]);
            if(expectedSeq == -1) expectedSeq = seq;

            if(type.equals("DATA_END")){
                System.out.println("Download finished. Saved as:" + savePath);
                String ackMsg = buildPkt("ACK", seq, 0, (seq+"").getBytes());
                socket.send(new DatagramPacket(ackMsg.getBytes(), ackMsg.length(), packet.getAddress(), packet.getPort()));
                break;
            }
            if(type.equals("DATA")){
                byte[] payload = p[3].isEmpty() ? new byte[0] : Base64.getDecoder().decode(p[3]);

                if(seq == expectedSeq){
                    fos.write(payload);
                    String ackMsg = buildPkt("ACK", seq, 0, (seq+"").getBytes());
                    socket.send(new DatagramPacket(ackMsg.getBytes(), ackMsg.length(), packet.getAddress(), packet.getPort()));
                    expectedSeq++;
                }else{
                    int lastAck = expectedSeq - 1;
                    String ackMsg = buildPkt("ACK", lastAck, 0, (lastAck    +"").getBytes());
                    socket.send(new DatagramPacket(ackMsg.getBytes(), ackMsg.length(), packet.getAddress(), packet.getPort()));
                }
            }
        }
        fos.close();
    }

    public void close() {
        socket.close();
    }

    // Request list of files
    public List<String> requestFileList(InetAddress serverIP, int serverPort) throws Exception {
        socket.send(new DatagramPacket("LIST".getBytes(), "LIST".length(), serverIP, serverPort));
        byte[] buf = new byte[4096];
        DatagramPacket resp = new DatagramPacket(buf, buf.length);
        socket.receive(resp);
        String msg = new String(resp.getData(), 0, resp.getLength());
        if(!msg.startsWith("FILELIST:")) return new ArrayList<>();
        return Arrays.asList(msg.substring(9).split("\\|"));
    }

    public static void main(String[] args) throws Exception {
        String serverIPStr = "127.0.0.1";
        int serverPort = 8000;

        UDP_Client client = new UDP_Client();
        InetAddress serverIP = InetAddress.getByName(serverIPStr);

        // Send SYN
        client.send("SYN", null, serverIP, serverPort);

        // Receive SYN-ACK and extract assigned port
        String synAck = client.receive();
        String[] synAckParts = synAck.split(":", 4);
        byte[] assignedPortBytes = Base64.getDecoder().decode(synAckParts[3]);
        String assignedPort = new String(assignedPortBytes);
        System.out.println("Assigned port from server: " + assignedPort);

        // Send CONFIRM with the assigned port echoed back as payload
        client.send("CONFIRM", assignedPort.getBytes(), serverIP, serverPort);

        // Receive PORT_CONFIRMED
        client.receive();

        // Send file
        Scanner sc = new Scanner(System.in);
        System.out.println("Choose:");
        System.out.println("1. Upload File");
        System.out.println("2. Download File");
        System.out.println("3. List Downloadable Files");
        String option = sc.nextLine().trim();

        if(option.equals("1")){
            System.out.println("Enter file to uplaod: ");
            String file = sc.nextLine().trim();
            client.send("UPLOAD", null, serverIP, serverPort);
            client.sendFile(file,serverIP, serverPort);
        }else if(option.equals("2")){
            System.out.println("Enter file to download: ");
            String fileName = sc.nextLine().trim();
            client.requestFile(fileName, serverIP, serverPort);
            client.receiveFile("downloads/" + fileName);
        }else if(option.equals("3")){
            List<String> files = client.requestFileList(serverIP, serverPort);
            System.out.println("Available files:");
            for(String f : files) System.out.println(" - " + f);
        }
    }
}
