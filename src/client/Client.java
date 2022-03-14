package client;

import java.net.*;
import java.util.Scanner;
import java.io.*;

public class Client {

    private static int serversocket = 6000;

    public static void main(String args[]) {
        // args[0] <- hostname of destination
        if (args.length == 0) {
            System.out.println("USAGE: java Client hostname");
            System.exit(0);
        }

        // criar socket
        try (Socket s = new Socket(args[0], serversocket)) {
            System.out.println("SOCKET=" + s);

            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            Receiver recv = new Receiver(in);
            Sender send = new Sender(out);
            recv.start();
            send.start();
            recv.join();
            send.join();


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

class Sender extends Thread {
    private DataOutputStream out;

    public Sender(DataOutputStream out)  {
        this.out = out;
    }

    public void run(){

        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                String line = sc.nextLine();
                this.out.writeUTF(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}


class Receiver extends Thread{

    private DataInputStream in;

    public Receiver(DataInputStream in)  {
        this.in = in;
    }

    public void run(){
        while (true) {
            try {
                System.out.print(in.readUTF());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }
}
