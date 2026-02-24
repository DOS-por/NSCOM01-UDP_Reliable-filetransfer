import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.*;
import java.util.*;

public class UDP_Server {

    class Session {
        InetAddress clientIP;
        int clientPort;
        int expectedSeq;
        int lastAckSent;
        int assignedPort;

        Session(InetAddress ip, int port, int initialSeq, int assignedPort) {
            this.clientIP = ip;
            this.clientPort = port;
            this.expectedSeq = initialSeq;
            this.lastAckSent = initialSeq - 1;
            this.assignedPort = assignedPort;
        }
    }

    private final String SERVER_FOLDER = "server_files"; // Folder containing downloadable files
    private int seqNum = new Random().nextInt(10000); // Initial server seq num
    private DatagramSocket socket;
    private Queue<Integer> openPorts = new LinkedList<>();
    private Map<String, Session> activeSessions = new HashMap<>();

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

    // Build packet: TYPE:SEQ:ACK:PAYLOAD
    private String buildPkt(String type, int seq, int ack, byte[] payload) {
        String data = payload != null ? Base64.getEncoder().encodeToString(payload) : "";
        return type + ":" + seq + ":" + ack + ":" + data;
    }

    // Handle handshake
    public Session handleHandshake() throws Exception {
        // --- Receive SYN
        byte[] buffer;
        DatagramPacket packet;
        String message, type;
        int clientSeq;
        InetSocketAddress clientAddress;

        // Keep looping until we get a valid SYN
        while (true) {
            try{
                socket.setSoTimeout(5000);
                buffer = new byte[1024];
                packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
            }catch(SocketTimeoutException e){
                System.out.println("Waiting for client..");
                continue;
            }

            clientAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
            message = new String(packet.getData(), 0, packet.getLength());
            System.out.println("Received: " + message);

            String[] p = message.split(":", 4);
            type = p[0];
            clientSeq = Integer.parseInt(p[1]);

            if (!type.equals("SYN")) {
                System.out.println("Ignoring non-SYN packet: " + message);
                continue; // go back and wait for next packet
            }
            break; // got SYN, proceed
        }
        socket.setSoTimeout(0);
        // Generate sessionKey for tracking
        String sessionKey = clientAddress.getAddress().getHostAddress() + ":" + clientAddress.getPort();

        // Assign port for client
        Integer assignedPort = openPorts.poll();
        if (assignedPort == null) {
            System.out.println("No ports available for new client");
            return null;
        }

        // Create Session
        Session session = new Session(packet.getAddress(), packet.getPort(), clientSeq + 1, assignedPort);
        activeSessions.put(sessionKey, session);

        // --- Send SYN-ACK (assigned port)
        String synAck = buildPkt("SYN-ACK", seqNum, clientSeq + 1, (assignedPort + "").getBytes());
        socket.send(new DatagramPacket(synAck.getBytes(), synAck.length(),
                clientAddress.getAddress(), clientAddress.getPort()));
        System.out.println("Sent: " + synAck);
        seqNum++;

        // --- Receive CONFIRM from client
        buffer = new byte[1024];
        DatagramPacket confirmPacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(confirmPacket);

        String confirmMsg = new String(confirmPacket.getData(), 0, confirmPacket.getLength());
        System.out.println("Received: " + confirmMsg);
        String[] c = confirmMsg.split(":", 4);

        // Decode the port echoed back by the client
        if (c[3].isEmpty()) {
            System.out.println("CONFIRM missing port payload. Dropping.");
            return null;
        }
        byte[] confirmPayload = Base64.getDecoder().decode(c[3]);
        int chosenPort = Integer.parseInt(new String(confirmPayload));

        // Verify session
        if (chosenPort != session.assignedPort) {
            System.out.println("Session mismatch! Dropping.");
            return null;
        }

        session.expectedSeq = Integer.parseInt(c[1]) + 1;

        String ack = buildPkt("PORT_CONFIRMED", seqNum, 0, (chosenPort + "").getBytes());
        socket.send(new DatagramPacket(ack.getBytes(), ack.length(),
                clientAddress.getAddress(), clientAddress.getPort()));
        System.out.println("Sent: " + ack);

        session.lastAckSent = chosenPort;
        seqNum++;

        System.out.println("Handshake complete with client " + sessionKey);
        return session;
    }

    // Send list of available server files to client
    public void sendFileList(Session session) throws Exception {
        File folder = new File(SERVER_FOLDER);
        File[] files = folder.listFiles();
        StringBuilder sb = new StringBuilder();
        for(File f : files){
            if(sb.length() > 0) sb.append("|");
            sb.append(f.getName());
        }
        String msg = "FILELIST:" + sb.toString();
        socket.send(new DatagramPacket(msg.getBytes(), msg.length(), session.clientIP, session.clientPort));
        System.out.println("Sent file list to client");
    }

    // Sends specified file from server_files/ to client
    public void sendFileToClient(Session session, String filename) throws Exception{
        File file = new File("server_files/" + filename);
        if(!file.exists()){
            System.out.println("File not found: "+ filename);
            String error = buildPkt("ERROR",0,0,"File not found".getBytes());
            socket.send(new DatagramPacket(error.getBytes(), error.length(), session.clientIP, session.clientPort));
            return;
        }

        FileInputStream curFile = new FileInputStream(file);
        byte[] fileBytes = curFile.readAllBytes();
        curFile.close();
        int seq = seqNum;
        int offset = 0;
        int chunkSize = 1024;

        while(offset < fileBytes.length){
            // Create the DATA pkt
            int len = Math.min(chunkSize, fileBytes.length - offset);
            byte[] chunk = Arrays.copyOfRange(fileBytes, offset, offset + len);
            String pkt = buildPkt("DATA", seq, 0 , chunk);
            socket.send(new DatagramPacket(pkt.getBytes(), pkt.length(), session.clientIP, session.clientPort));
            System.out.println("Sent DATA seq=" + seq + " size=" + chunk.length);

            // Wait for ACK
            byte[] ackBuf = new  byte[1024];
            DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
            socket.receive(ackPacket);
            String ackMsg = new String(ackPacket.getData(), 0, ackPacket.getLength());
            System.out.println("Received: " + ackMsg);
            if(!ackMsg.contains("ACK:" + seq)){
                System.out.println("ACK mismatch! Resending seq=" + seq);
                continue;
            }
            seq++;
            seqNum++;
            offset += len;
        }

        // Send DATA_END
        String endPkt = buildPkt("DATA_END", seq, 0, (seq+"").getBytes());
        socket.send(new DatagramPacket(endPkt.getBytes(), endPkt.length(), session.clientIP, session.clientPort));
        System.out.println("File transfer complete.");
    }

    // Receive an uploaded file from client
    public void recvFile(String savePath, Session session) throws Exception {
        FileOutputStream fileOut = new FileOutputStream(savePath);
        byte[] buffer = new byte[4096];
        int expectedSeq = session.expectedSeq;

        try {
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength());
                String[] parts = msg.split(":", 4);
                String type = parts[0];
                int seq = Integer.parseInt(parts[1]);

                if (type.equals("DATA_END")) {
                    System.out.println("Received DATA_END. File saved at " + savePath);
                    String ackMsg = buildPkt("ACK",seq,0,(seq+"").getBytes());
                    socket.send(new DatagramPacket(ackMsg.getBytes(), ackMsg.length(), session.clientIP, session.clientPort));
                    break;
                }

                if (type.equals("DATA")) {
                   byte[] payload = (parts.length > 3 && !parts[3].isEmpty())
                            ? Base64.getDecoder().decode(parts[3])
                            : new byte[0];

                    if (seq == expectedSeq) {
                        fileOut.write(payload);
                        // Payload carries the seq number so client can match ACK
                        String ackMsg = buildPkt("ACK", seq, 0, (seq + "").getBytes());
                        socket.send(new DatagramPacket(ackMsg.getBytes(), ackMsg.length(),
                                session.clientIP, session.clientPort));
                        System.out.println("Received DATA seq=" + seq + " size=" + payload.length + ", sent ACK");
                        expectedSeq++;

                    } else if (seq < expectedSeq) {
                        int lastAck = expectedSeq - 1;
                        String ackMsg = buildPkt("ACK", lastAck, 0, (lastAck + "").getBytes());
                        socket.send(new DatagramPacket(ackMsg.getBytes(), ackMsg.length(),
                                session.clientIP, session.clientPort));
                        System.out.println("Duplicate DATA seq=" + seq + ", resent ACK seq=" + lastAck);
                    }
                }

            }
        } finally {
            fileOut.close();
        }
    }

    // Main
    public static void main(String[] args) throws Exception {
        UDP_Server server = new UDP_Server("127.0.0.1", 8000);
        System.out.println("Server ready...");

        while (true) {
            Session session = server.handleHandshake();
            if (session == null) {
                continue;
            }
            byte[] buf = new byte[2048];
            DatagramPacket req = new DatagramPacket(buf,buf.length);
            server.socket.receive(req);
            String msg = new String(req.getData(), 0, req.getLength());
            System.out.println("Client req: " + msg);

            if(msg.startsWith("REQ:")){ // Server -> Client
                String[] reqParts = msg.split(":", 4);
                session.expectedSeq = Integer.parseInt(reqParts[1]) + 1;
                byte[] filenameBytes = Base64.getDecoder().decode(reqParts[3]);
                String filename = new String(filenameBytes);
                server.sendFileToClient(session,filename);
            }else if(msg.startsWith("UPLOAD:")){ // CLient -> Server
                String[] reqParts = msg.split(":", 4);
                session.expectedSeq = Integer.parseInt(reqParts[1]) + 1;
                server.recvFile("uploads/" + "uploaded_file_" + System.currentTimeMillis(), session);
            }else if(msg.equals("LIST")){
                server.sendFileList(session);
            }
        }
    }
}