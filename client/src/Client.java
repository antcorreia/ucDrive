import java.net.*;
import java.util.ArrayList;
import java.util.Objects;
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
            // reconnection starts at false, if eventually is need turn true in specified region
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
                System.out.println("server: exiting");

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
        private String currentDir = "home";

        public Sender(DataOutputStream out,BlockingQueue<Boolean> q,Scanner sc)  {
            this.queue = q;
            this.out = out;
            this.sc = sc;
        }

        public void run(){

            try  {
                while (true) {
                    String line = sc.nextLine();

                    if (line.equals("local")) {
                        System.out.print("local /" + currentDir + " > ");
                        do {
                            line = sc.nextLine();

                            System.out.print(localCommandHandler(line));

                        } while (!(line.equals("server") || line.equals("exit")));
                    }

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

        public String localCommandHandler(String command) {
            try {
                if(command.equals("help")){
                    String commands = "";
                    commands += "\n\tserver - change to server files\n";
                    commands += "\texit - leave server\n";
                    commands += "\tcd - home directory\n";
                    commands += "\tcd .. - previous directory\n";
                    commands += "\tcd dir - go to dir directory\n";
                    commands += "\tmkdir dir - create dir directory\n";
                    commands += "\tupload filename /home/dir/ - create dir directory\n";
                    return commands + "\nlocal /" + currentDir + " > ";
                }

                else if(command.equals("exit") || command.equals("server")){
                    return "";
                }

                else if(command.startsWith("cd")){
                    if(command.equals("cd")){
                        currentDir = "home";
                        return "local /" + currentDir + " > ";
                    }
                    else{
                        String nextCommand = command.substring(2);
                        if(nextCommand.equals(" ..")){
                            if(currentDir.equals("home")){
                                return "local /" + currentDir + " >";
                            }
                            else {
                                currentDir = currentDir.substring(0, currentDir.lastIndexOf("/"));
                                return "local /" + currentDir + " > ";
                            }
                        }
                        else if(nextCommand.charAt(0) == ' '){
                            String nextDir = nextCommand.substring(1);
                            String BASE_DIR = System.getProperty("user.dir");
                            File directory = new File(BASE_DIR + "/" + currentDir + "/" + nextDir);
                            if (directory.exists()) {
                                currentDir = currentDir + "/" + nextDir;
                                return "local /" + currentDir + " > ";
                            }
                            else
                                return "local - folder doesn't exist\nlocal /"  + currentDir + " > ";
                        }
                    }
                }

                else if (command.startsWith("mkdir ")) {
                    String newFolder = command.substring(6);
                    String BASE_DIR = System.getProperty("user.dir");
                    File directory = new File(BASE_DIR + "/" + currentDir + "/" + newFolder);
                    if (!directory.exists()){
                        if (directory.mkdirs()) {
                            System.out.println("local - directory has been created successfully");
                            currentDir = currentDir + "/" + newFolder;
                            return "local /" + currentDir + " > ";
                        }
                        else {
                            return "local - directory cannot be created\nlocal /"  + currentDir + " > ";
                        }
                    }
                    else {
                        currentDir = currentDir + "/" + newFolder;
                        return "local - directory already exists\nlocal /" + currentDir + " > ";
                    }
                }

                else if(command.equals("ls")){
                    String BASE_DIR = System.getProperty("user.dir");
                    File directory = new File(BASE_DIR + "/" + currentDir);

                    int biggestLen = 0;
                    for (File file : Objects.requireNonNull(directory.listFiles()))
                        if (file.getName().length() > biggestLen)
                            biggestLen = file.getName().length();

                    int count = 1;
                    StringBuilder output = new StringBuilder("");
                    for (File file : Objects.requireNonNull(directory.listFiles())){
                        output.append(file.getName());
                        if (count % 5 == 0)
                            output.append("\n");
                        else {
                            output.append(" ".repeat(Math.max(0, biggestLen - file.getName().length())));
                            output.append("\t");
                        }
                        count++;
                    }

                    if (--count % 5 != 0)
                        output.append("\n");
                    output.append("local /").append(currentDir).append(" > ");

                    return output.toString();
                }

                else if(command.startsWith("upload ")){
                    String[] info = command.split(" ");
                    String BASE_DIR = System.getProperty("user.dir");
                    File directory = new File(BASE_DIR + "/" + currentDir + "/" + info[1]);
                    if (!directory.exists()){
                        return "local: file does not exist" +
                                "\nlocal /" + currentDir + " > ";
                    }
                    out.writeUTF("upload "+info[2]+info[1]);

                    String filepath = BASE_DIR + "/" + currentDir +"/"+ info[1];
                    sendFile(filepath);
                    return "local: upload complete\nlocal /" + currentDir + " > ";
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            return "invalid command\nlocal /" + currentDir + " > ";
        }

        public void sendFile(String path) throws Exception{
            int bytes = 0;
            File file = new File(path);
            FileInputStream fileInputStream = new FileInputStream(file);

            // send file size
            out.writeLong(file.length());
            // break file into chunks
            byte[] buffer = new byte[4*1024];
            while ((bytes=fileInputStream.read(buffer))!=-1){
                out.write(buffer,0,bytes);
                out.flush();
            }
            fileInputStream.close();
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
                    if(!fromServer.equals("")) {
                        if (fromServer.charAt(0) == '/') {
                            if (fromServerHandler(fromServer))
                                break;
                        } else {
                            System.out.print(fromServer);
                        }
                    }
                } catch (EOFException e) {
                System.out.println("EOF:" + e.getMessage());}
                catch (IOException e) {
                    e.printStackTrace();
                }

            }

        }

        private void receiveFile(String fileName) throws Exception{
            int bytes = 0;
            FileOutputStream fileOutputStream = new FileOutputStream(fileName);

            long size = in.readLong();     // read file size
            byte[] buffer = new byte[4*1024];
            while (size > 0 && (bytes = in.read(buffer, 0, (int)Math.min(buffer.length, size))) != -1) {
                fileOutputStream.write(buffer,0,bytes);
                size -= bytes;      // read upto file size
            }
            fileOutputStream.close();
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

            else if (command.startsWith("/file_download ")){
                String[] info = command.split(" ");
                try {
                    String BASE_DIR = System.getProperty("user.dir");
                    String dir =  info[1].substring(0,info[1].lastIndexOf("/")); // get dir until file
                    File directory = new File(BASE_DIR + dir);
                    if (!directory.exists()) {
                        if (directory.mkdirs()) {
                            receiveFile(BASE_DIR + info[1]);
                        } else {
                            System.out.println("local: an error ocurred while creating folder");
                        }
                    }
                    else {
                        receiveFile(BASE_DIR + info[1]);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return false;
        }
    }

}

