import java.net.*;
import java.util.ArrayList;
import java.util.Scanner;
import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Client {

    private static boolean reconnect = true;

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        String serverAddress;
        int serverSocket;

        ArrayList<String> connectionInfo = getConnectionInfo(sc);
        serverAddress = connectionInfo.get(0);
        serverSocket = Integer.parseInt(connectionInfo.get(1));

        while(reconnect) {
            // reconnection starts at false, if eventually is need turn true in specificr region
            reconnect = false;
            // criar socket
            try (Socket s = new Socket(serverAddress, serverSocket)) {
                System.out.println("SOCKET=" + s);

                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                BlockingQueue<Boolean> queue = new LinkedBlockingQueue<>(1);

                Receiver recv = new Receiver(in,queue);
                Sender send = new Sender(out,queue,sc);

                recv.start();
                send.start();
                recv.join();
                send.join();

                s.close();
                in.close();
                out.close();
                System.out.println("Server - Exiting");

            } catch (UnknownHostException e) {
                System.out.println("Sock:" + e.getMessage());
            } catch (EOFException e) {
                System.out.println("EOF:" + e.getMessage());
            } catch (IOException e) {
                System.out.println("IO:" + e.getMessage());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public static ArrayList<String> getConnectionInfo(Scanner sc) {
        ArrayList<String> info = new ArrayList<>();

        System.out.print("Insert the Primary Server IP Address: ");
        info.add(sc.nextLine());
        System.out.print("Insert the Primary Server Port: ");
        info.add(sc.nextLine());


        /*System.out.print("Insert the Secondary Server IP Address: ");
        info.add(sc.nextLine());
        System.out.print("Insert the Secondary Server Port: ");
        info.add(sc.nextLine());*/

        return info;
    }

    static class Sender extends Thread {
        private DataOutputStream out;
        private BlockingQueue<Boolean> queue;
        private Scanner sc;

        public Sender(DataOutputStream out,BlockingQueue<Boolean> q,Scanner sc)  {
            this.queue = q;
            this.out = out;
            this.sc = sc;
        }

        public void run(){

            try  {
                while (true) {
                    String line = sc.nextLine();
                    this.out.writeUTF(line);
                    if(toServerhandler(line,sc)) { // only way of stopping blocking scanner is to handle input
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        public boolean toServerhandler(String command,Scanner sc){
            try {
                if (command.equals("rp")) { // easies way of stopping blocking scanner is using if clause
                    command = sc.nextLine(); // read new password
                    this.out.writeUTF(command); // send it
                    // an error as ocurred
                    return queue.take();
                }
                else if (command.equals("exit")){
                    return true;
                }

            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
            }
            return false;
        }

    }

    static class Receiver extends Thread{

        private DataInputStream in;
        BlockingQueue<Boolean> queue;
        public Receiver(DataInputStream in,BlockingQueue<Boolean> q)  {
            this.queue = q;
            this.in = in;
        }

        public void run(){

            while (true) {
                try {
                    String fromServer = in.readUTF();
                    if(fromServer.charAt(0)=='/'){
                        if(fromServerHandler(fromServer))
                            break;
                    }
                    else{
                        System.out.print(fromServer);
                    }
                } catch (EOFException e) {
                System.out.println("EOF:" + e.getMessage());}
                catch (IOException e) {
                    e.printStackTrace();
                }

            }

        }

        /**
         * server commands receveid to be executed by client
         * @param command command
         * @return true to break recv, false if no thread action is needed
         */
        public boolean fromServerHandler(String command){
            if (command.equals("/reconnect")){
                reconnect = true;
                try {
                    queue.put(true);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }
            else if (command.equals("/exit")){
                return true;
            }
            // outros commandos q possam vir a aparecer

            return false;
        }
    }

}

