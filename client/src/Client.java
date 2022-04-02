import java.net.*;
import java.io.*;

import java.util.*;
import java.util.concurrent.*;

public class Client {

    private static int reconnect = 1; // 0 dont reconnect, 1 reconnect, 2 connection lost
    private static String serverAddress; // here for use in receive file to know ip

    /**
     * > Main;
     * > It will run until exit command is performed;
     * > Variable reconnect will determine operations;
     * @param args not used;
     */
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        while (reconnect != 0) {

            // Connection was lost
            if (reconnect == 2) reconnect = 1;

            ArrayList<String> connectionInfo = getConnectionInfo(sc);
            serverAddress = connectionInfo.get(0);
            int serverSocket = Integer.parseInt(connectionInfo.get(1));

            while (reconnect == 1) {

                // reconnection starts at 0, if eventually is need turn 1 in specified region
                reconnect = 0;

                try {

                    // Create socket
                    Socket s = new Socket(serverAddress, serverSocket);
                    System.out.println("ucdrive - connection information: " + s);

                    // Create objects
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    BlockingQueue<Integer> queue = new LinkedBlockingQueue<>(1);
                    Receiver recv = new Receiver(in, queue);
                    Sender send = new Sender(out, queue, sc);

                    // Start threads
                    recv.start();
                    send.start();

                    // Join threads
                    recv.join();
                    send.join();

                    // Close threads
                    s.close();
                    in.close();
                    out.close();

                    if (reconnect == 1) System.out.println("ucdrive - reconnecting");
                    if (reconnect == 2) System.out.println("ucdrive - connection lost, wait a few seconds before tryng to connect to backup server");
                    else System.out.println("ucdrive - exiting");

                }
                catch (InterruptedException | IOException e) {
                    System.out.println("ucdrive - something went wrong");
                    reconnect = 2;
                }
            }
        }
    }

    /**
     * > Used to scan input for server ip and port;
     * @param sc scanner, received in parameter. Because is scanner is not thread safe;
     * @return arraylist with ip and port;
     */
    public static ArrayList<String> getConnectionInfo(Scanner sc) {
        ArrayList<String> info = new ArrayList<>();

        // Get info
        System.out.print("ucdrive - insert ip adress: ");
        info.add(sc.nextLine());
        System.out.print("ucdrive - insert comunication port: ");
        info.add(sc.nextLine());

        return info;
    }

    /**
     * > Sender class thread, sends all user input
     */
    static class Sender extends Thread {
        private final DataOutputStream out;
        private final BlockingQueue<Integer> queue;
        private final Scanner sc;
        private String currentDir = "home";

        public Sender(DataOutputStream out,BlockingQueue<Integer> q,Scanner sc)  {
            this.queue = q;
            this.out = out;
            this.sc = sc;
        }

        /**
         * running thread, scans input and handles it
         * if 'local' is used all commands are no sented, with the expection of 'server' to reconnect and 'exit' to exit
         * if leaving local, queue has someting, it means the receiver thread sended someting, in this case it means
         * the connection to server was lost, reconnect it set to 2. if not command is sent. Because the comands
         * to reset password and exit the thread must be closed, this inputs are handled in 'toserverhabndler'
         */
        public void run(){

            try  {
                while (true) {
                    String line = sc.nextLine();

                    if (line.equals("local")) {
                        System.out.print("local /" + currentDir + " > ");

                        while(true){
                            line = sc.nextLine();
                            String res = localCommandHandler(line);

                            if(res.equals("")) break;
                            System.out.print(res);
                        }
                    }

                    if(!queue.isEmpty()){ // The only way for the queue to not be empty is if it crashed
                        int a = queue.take();

                        if(a == 2){ // Double check
                            reconnect = a;
                            break;
                        }
                    }
                    this.out.writeUTF(line);

                    // The only way to stop the scanner is to handle input
                    if (toServerHandler(line, sc)==1) break;
                }
            }
            catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }

        }

        /**
         * > Handle client input to determine if command is to exit thread;
         * @param command command sent to server;
         * @param sc scanner;
         * @return 1 to close thread, 0 no action needed;
         */
        public int toServerHandler(String command, Scanner sc){
            try {
                if (command.equals("rp")) { // Easiest way of stopping blocking scanner is using an if clause
                    command = sc.nextLine(); // Read new password
                    this.out.writeUTF(command); // Send it

                    // An error has occurred ?
                    return queue.take();
                }
                else if (command.equals("exit")) return 1;
            }
            catch (InterruptedException | IOException e) {
                e.printStackTrace();
            }
            return 0;
        }

        /**
         * > When in local mode, all commands are handled by this function;
         * @param command command read;
         * @return string to print;
         */
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
                    commands += "\tupload filename /home/path/ - upload file to server path\n";

                    return commands + "\nlocal /" + currentDir + " > ";
                }

                else if(command.equals("exit") || command.equals("server")) return "";
                else if(command.startsWith("cd")){
                    if(command.equals("cd")){
                        currentDir = "home";

                        return "local /" + currentDir + " > ";
                    }
                    else{
                        String nextCommand = command.substring(2);
                        if(nextCommand.equals(" ..")){
                            if(currentDir.equals("home")) return "local /" + currentDir + " >";
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
                        else return "local - directory cannot be created\nlocal /"  + currentDir + " > ";
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
                    StringBuilder output = new StringBuilder();
                    for (File file : Objects.requireNonNull(directory.listFiles())){
                        output.append(file.getName());

                        if (count % 5 == 0) output.append("\n");
                        else {
                            output.append(" ".repeat(Math.max(0, biggestLen - file.getName().length())));
                            output.append("\t");
                        }

                        count++;
                    }

                    if (--count % 5 != 0) output.append("\n");
                    output.append("local /").append(currentDir).append(" > ");

                    return output.toString();
                }

                else if(command.startsWith("upload ")){

                    // The only way for the queue to be full on local is if connection was lost
                    if(!queue.isEmpty()) return "";

                    String[] info = command.split(" ");
                    String BASE_DIR = System.getProperty("user.dir");
                    File directory = new File(BASE_DIR + "/" + currentDir + "/" + info[1]);

                    if (!directory.exists()){
                        return "local: file does not exist" +
                                "\nlocal /" + currentDir + " > ";
                    }

                    // Get available port
                    ServerSocket s = new ServerSocket(0);
                    out.writeUTF("upload "+info[2]+info[1]+ " " + s.getLocalPort());

                    String filepath = BASE_DIR + "/" + currentDir +"/"+ info[1];
                    sendFile(filepath,s);
                    return "local: upload complete\nlocal /" + currentDir + " > ";
                }

            }
            catch (Exception e) {
                e.printStackTrace();
            }

            return "invalid command\nlocal /" + currentDir + " > ";
        }

        /**
         * > Function used to upload files;
         * @param path path where file is;
         */
        public void sendFile(String path, ServerSocket s){
            try {
                Socket cs = s.accept(); // Waiting for server
                DataOutputStream out = new DataOutputStream(cs.getOutputStream());

                int bytes;
                File file = new File(path);
                FileInputStream fileInputStream = new FileInputStream(file);

                // Send file size
                out.writeLong(file.length());

                // Break file into chunks
                byte[] buffer = new byte[4 * 1024];
                while ((bytes = fileInputStream.read(buffer)) != -1) {
                    out.write(buffer, 0, bytes);
                    out.flush();
                }

                fileInputStream.close();
                cs.close();
                s.close();
            }
            catch (Exception e) {
                System.out.println("DEBUG: something went wrong with the upload");
            }
        }
    }

    /**
     * > Receiver class thread, receives all input from server;
     */
    static class Receiver extends Thread{

        private final DataInputStream in;
        BlockingQueue<Integer> queue;

        public Receiver(DataInputStream in,BlockingQueue<Integer> q)  {
            this.queue = q;
            this.in = in;
        }

        /**
         * > Runner will loop in while true, commands to execute by server come with a '/' at the start, otherwise
         *   it is just a print. Commands from server are handled in 'fromServerHandler' is true for exit or reconnect;
         */
        public void run(){

            while (true) {
                try {
                    String fromServer = in.readUTF();
                    if(!fromServer.equals("")) {
                        if (fromServer.charAt(0) == '/') {
                            if (fromServerHandler(fromServer))
                                break;
                        }
                        else
                            System.out.print(fromServer);
                    }
                }
                catch (IOException e) {
                    try {
                        queue.put(2);
                    }
                    catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }

                    break;
                }
            }
        }

        /**
         * > File downloads are made here;
         * @param fileName path where file is stored;
         * @param port server port;
         */
        private void receiveFile(String fileName, int port){
            try {
                // Create socket
                Socket s = new Socket(serverAddress, port);

                // Initialize variables
                DataInputStream in = new DataInputStream(s.getInputStream());
                int bytes;
                FileOutputStream fileOutputStream = new FileOutputStream(fileName);

                long size = in.readLong();     // Read file size
                byte[] buffer = new byte[4 * 1024];
                while (size > 0 && (bytes = in.read(buffer, 0, (int) Math.min(buffer.length, size))) != -1) {
                    fileOutputStream.write(buffer, 0, bytes);
                    size -= bytes;      // Read upto file size
                }

                // Close stream and socket
                fileOutputStream.close();
                s.close();
            }
            catch (IOException e) {
                System.out.println("DEBUG: something went wrong with the download");
            }
        }

        /**
         * > Server commands received to be executed by client;
         * @param command command;
         * @return true to break recv, false if no thread action is needed;
         */
        public boolean fromServerHandler(String command){
            if (command.equals("/reconnect")){
                reconnect = 1;
                try {
                    queue.put(1);
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }

                return true;
            }

            else if (command.equals("/exit")) return true;

            else if (command.startsWith("/file_download ")){
                String[] info = command.split(" ");

                try {
                    String BASE_DIR = System.getProperty("user.dir");
                    String dir =  info[1].substring(0,info[1].lastIndexOf("/")); // get dir until file
                    File directory = new File(BASE_DIR + dir);

                    if (!directory.exists()) {
                        if (directory.mkdirs()) receiveFile(BASE_DIR + info[1],Integer.parseInt(info[2]));
                        else System.out.println("local: an error ocurred while creating folder");
                    }
                    else receiveFile(BASE_DIR + info[1],Integer.parseInt(info[2]));

                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }

            return false;
        }
    }
}

