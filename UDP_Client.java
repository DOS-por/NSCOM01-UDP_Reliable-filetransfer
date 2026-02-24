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
        int maxRetries = 5; // max retries per packet

        while ((bytesRead = fileInput.read(buffer)) != -1) {
            byte[] payload = Arrays.copyOf(buffer, bytesRead);
            boolean ackRecv = false;
            int retryCount = 0;

            while (!ackRecv && retryCount < maxRetries) {
                try {
                    // Send DATA packet
                    String packet = buildPkt("DATA", seqNum, 0, payload);
                    socket.send(new DatagramPacket(packet.getBytes(), packet.length(), serverIP, serverPort));
                    System.out.println("Sent DATA seq=" + seqNum + " size=" + bytesRead);

                    // Wait for ACK
                    socket.setSoTimeout(2000);
                    byte[] ackBuf = new byte[2048];
                    DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
                    socket.receive(ackPacket);

                    String ackMsg = new String(ackPacket.getData(), 0, ackPacket.getLength());
                    String[] parts = ackMsg.split(":", 4);
                    String type = parts[0];

                    int ackNumRecv = -1;
                    if (type.equals("ACK") && parts.length > 3 && !parts[3].isEmpty()) {
                        byte[] ackPayload = Base64.getDecoder().decode(parts[3]);
                        ackNumRecv = Integer.parseInt(new String(ackPayload));
                    }

                    if (ackNumRecv == seqNum) {
                        ackRecv = true;
                        seqNum++;
                    } else {
                        retryCount++;
                        System.out.println("ACK mismatch, resending seq=" + seqNum + " (retry " + retryCount + ")");
                    }

                } catch (SocketTimeoutException e) {
                    retryCount++;
                    System.out.println("Timeout, resending seq=" + seqNum + " (retry " + retryCount + ")");
                }
            }

            if (!ackRecv) {
                System.out.println("Failed to send packet seq=" + seqNum + " after " + maxRetries + " retries. Aborting upload.");
                fileInput.close();
                return;
            }
        }

        fileInput.close();
        socket.setSoTimeout(0); // reset timeout

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
        int maxRetries = 5; // max retries if a packet is missing
        int retryCount;
        boolean receivedPacket;

        System.out.println("Receiving file...");
        socket.setSoTimeout(2000); // wait 2 seconds for each packet

        try {
            while (true) {
                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                retryCount = 0;
                receivedPacket = false;

                while (!receivedPacket && retryCount < maxRetries) {
                    try {
                        socket.receive(packet);
                        receivedPacket = true;

                        String msg = new String(packet.getData(), 0, packet.getLength());
                        System.out.println("Received: " + msg);

                        String[] p = msg.split(":", 4);
                        String type = p[0];
                        int seq = Integer.parseInt(p[1]);
                        if (expectedSeq == -1) expectedSeq = seq;

                        if (type.equals("DATA_END")) {
                            System.out.println("Download finished. Saved as: " + savePath);
                            String ackMsg = buildPkt("ACK", seq, 0, (seq + "").getBytes());
                            socket.send(new DatagramPacket(ackMsg.getBytes(), ackMsg.length(), packet.getAddress(), packet.getPort()));
                            return; // finished
                        }

                        if (type.equals("DATA")) {
                            byte[] payload = (p.length > 3 && !p[3].isEmpty()) ? Base64.getDecoder().decode(p[3]) : new byte[0];

                            if (seq == expectedSeq) {
                                fos.write(payload);
                                String ackMsg = buildPkt("ACK", seq, 0, (seq + "").getBytes());
                                socket.send(new DatagramPacket(ackMsg.getBytes(), ackMsg.length(), packet.getAddress(), packet.getPort()));
                                expectedSeq++;

                            } else if (seq < expectedSeq) {
                                // duplicate packet
                                int lastAck = expectedSeq - 1;
                                String ackMsg = buildPkt("ACK", lastAck, 0, (lastAck + "").getBytes());
                                socket.send(new DatagramPacket(ackMsg.getBytes(), ackMsg.length(), packet.getAddress(), packet.getPort()));
                            }
                        }

                    } 
                    catch (SocketTimeoutException e) 
                    {
                        retryCount++;
                        System.out.println("Timeout waiting for packet seq=" + expectedSeq + ", retry " + retryCount);
                    }
                }

                if (!receivedPacket) {
                    System.out.println("Failed to receive packet seq=" + expectedSeq + " after " + maxRetries + " retries. Aborting download.");
                    return;
                }
            }
        } 
        finally 
        {
            fos.close();
        }
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
        if (!msg.startsWith("FILELIST:")) return new ArrayList<>();
        String fileList = msg.substring(9); // remove "FILELIST:"
        // Handle empty list
        if (fileList.trim().isEmpty() || fileList.equalsIgnoreCase("No files available")) {
            System.out.println("No files available on the server.");
            return new ArrayList<>();
        }
        // Return list of files
        return Arrays.asList(fileList.split("\\|"));
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
        System.out.println("4. End Session");
        String input = sc.nextLine().trim();
        int option = Integer.parseInt(input);


        switch(option) {
            case 1:
                System.out.println("Enter file to uplaod: ");
                String file = sc.nextLine().trim();
                client.send("UPLOAD", file.getBytes(), serverIP, serverPort);
                client.sendFile(file,serverIP, serverPort);
                break;
            case 2:
                System.out.println("Enter file to download: ");
                String fileName = sc.nextLine().trim();
                client.requestFile(fileName, serverIP, serverPort);
                client.receiveFile("downloads/" + fileName);
                break;
            case 3:
                List<String> files = client.requestFileList(serverIP, serverPort);
                System.out.println("Available files:");
                for(String f : files) System.out.println(" - " + f);
                break;
            case 4:
                client.send("FIN",null, serverIP, serverPort);
                System.out.println("Session Ended...");
                break;
        }

        sc.close();
    }
}
