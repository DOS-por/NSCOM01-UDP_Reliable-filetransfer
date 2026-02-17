import java.util.*;
import java.io.IOException;
import java.net.*;

public class UDP_Client {
    
    //Constructor
    public UDP_Client(String ipAdd, int port) 
    {
        try 
        {
            //IP add set up
            InetAddress clientIp = InetAddress.getByName(ipAdd);
            System.out.println("IP Address finished set up");

            //Port set up
            socket = new DatagramSocket(port);
            System.out.println("Server Receive Port:" + port);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    //Methods(functions)
    public void send(String message, InetAddress targetIP, int targetPort) 
    {
        
        try 
        {
            byte[] data = message.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, targetIP, targetPort);
            socket.send(packet);
            System.out.println("Message sent");
        } catch (Exception e) 
        {
            System.out.println("Error sending message: " + e.getMessage());
        }
    }

    public String receive(String reply)
    {
    
        try 
        {
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            reply = new String(packet.getData(), 0, packet.getLength());
        }
        catch (Exception e)
        {
            reply = e.getMessage();
        }

        return reply;
    }

    //Attributes
    private DatagramSocket socket;

    //Main
    public static void main(String[] args) throws Exception{
        Scanner sc = new Scanner(System.in);

        System.out.println("Please Enter Your IP Address");
        String ipAdd = sc.nextLine();

        System.out.println("Please Enter Desired Port to Receive");
        int port = sc.nextInt();

        UDP_Client client = new UDP_Client(ipAdd, port);
        
        boolean stop = false;
        while (!stop) 
        {
            // Send a message
            System.out.print("Enter message to send (type 'exit' to stop): ");
            String msg = sc.nextLine();
            if (msg.equalsIgnoreCase("exit")) {
                stop = true;
                continue;
            }

            System.out.println("Please Enter targetIP:");
            String targetIP = sc.nextLine();
            InetAddress targetAddress = InetAddress.getByName(targetIP);

            System.out.println("Please Enter destPort:");
            int destPort = sc.nextInt();
            sc.nextLine();

            client.send(msg, targetAddress, destPort);

            // Receive a message
            String reply = "";
            reply = client.receive(reply);
            System.out.println("Received: " + reply);
        }
    }
}