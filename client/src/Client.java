import java.net.*;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Scanner;
import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Client {

    private static int reconnect = 1; // 0 dont reconnect, 1 reconnect, 2 connection lost
    private static String serverAddress; // here for use in receive file to know ip

    /**
     * main
     * it will run until exit command is preformed
     * variable reconnect will determine operations
     * @param args not used
     */
    public static void main(String[] args) {


        Scanner sc = new Scanner(System.in);

        while(true) {

            if(reconnect==0){ // exit server
                break;
            }
            if(reconnect==2){ // conection was lost
                reconnect =1;
            }

            ArrayList<String> connectionInfo = getConnectionInfo(sc);
            serverAddress = connectionInfo.get(0);
            int serverSocket = Integer.parseInt(connectionInfo.get(1));

            while (reconnect==1) {
                // reconnection starts at 0, if eventually is need turn 1 in specified region
                reconnect = 0;
                // criar socket
                try (Socket s = new Socket(serverAddress, serverSocket)) {
                    System.out.println("ucdrive - connection information: " + s);
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    BlockingQueue<Integer> queue = new LinkedBlockingQueue<>(1);

                    Receiver recv = new Receiver(in, queue);
                    Sender send = new Sender(out, queue, sc);

                    recv.start();
                    send.start();
                    recv.join();
                    send.join();

                    s.close();
                    in.close();
                    out.close();
                    if(reconnect==1){
                        System.out.println("ucdrive - reconnecting");
                    }
                    if(reconnect==2){
                        System.out.println("ucdrive - connection lost, wait a few seconds before tryng to connect to backup server");
                    }
                    else{
                        System.out.println("ucdrive - exiting");
                    }

                } catch (InterruptedException | IOException e) {
                    System.out.println("ucdrive - something went wrong");
                    reconnect = 2;
                }
            }
        }
    }

    /**
     * used to scan input for server ip and port
     * @param sc scanner, received in paramenter because is scanner is not thread safe
     * @return arrayslist with ip and port
     */
    public static ArrayList<String> getConnectionInfo(Scanner sc) {
        ArrayList<String> info = new ArrayList<>();

        System.out.print("ucdrive - insert ip adress: ");
        info.add(sc.nextLine());
        System.out.print("ucdrive - insert comunication port: ");
        info.add(sc.nextLine());

        return info;
    }

    /**
     * sender class thread, sends all user input
     */
    static class Sender extends Thread {
        private DataOutputStream out;
        private BlockingQueue<Integer> queue;
        private Scanner sc;
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
                            if(res.equals("")){
                                break;
                            }
                            System.out.print(res);

                        }
                    }

                    if(!queue.isEmpty()){ // only way for queue is not empty is if it crashed
                        int a = queue.take();
                        if(a == 2){ // verify anyway ...
                            reconnect = a;
                            break;
                        }
                    }

                    this.out.writeUTF(line);
                    if(toServerhandler(line,sc)==1) { // only way of stopping blocking scanner is to handle input
                        break;
                    }
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }

        }

        /**
         * handle client input to determine if command is to exit thread
         * @param command command sent to server
         * @param sc scanner
         * @return 1 to close thread, 0 no action needed
         */
        public int toServerhandler(String command,Scanner sc){
            try {
                if (command.equals("rp")) { // easies way of stopping blocking scanner is using if clause
                    command = sc.nextLine(); // read new password
                    this.out.writeUTF(command); // send it
                    // an error as ocurred ?
                    return queue.take();
                }
                else if (command.equals("exit")){
                    return 1;
                }
            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
            }
            return 0;
        }

        /**
         * when in local mode, all commands are handled by this function
         * @param command command read
         * @return string to print
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

                    if(!queue.isEmpty()){
                        // the only way for queue to be full on local is if connection was lost
                        return "";
                    }

                    String[] info = command.split(" ");
                    String BASE_DIR = System.getProperty("user.dir");
                    File directory = new File(BASE_DIR + "/" + currentDir + "/" + info[1]);
                    if (!directory.exists()){
                        return "local: file does not exist" +
                                "\nlocal /" + currentDir + " > ";
                    }
                    ServerSocket s = new ServerSocket(0); // get available port
                    out.writeUTF("upload "+info[2]+info[1]+ " " + s.getLocalPort());

                    String filepath = BASE_DIR + "/" + currentDir +"/"+ info[1];
                    sendFile(filepath,s);
                    return "local: upload complete\nlocal /" + currentDir + " > ";
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            return "invalid command\nlocal /" + currentDir + " > ";
        }

        /**
         * funtion used to upload files
         * @param path path where file is
         */
        public void sendFile(String path, ServerSocket s){
            try {
                Socket cs = s.accept(); // BLOQUEANTE waiting for server
                DataInputStream in = new DataInputStream(cs.getInputStream());
                DataOutputStream out = new DataOutputStream(cs.getOutputStream());

                int bytes = 0;
                File file = new File(path);
                FileInputStream fileInputStream = new FileInputStream(file);

                // send file size
                out.writeLong(file.length());
                // break file into chunks
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
                System.out.println("ucdrive - something went wrong with the download");
            }
        }
    }

    /**
     * receiver class thread, receives all input from server
     */
    static class Receiver extends Thread{

        private DataInputStream in;
        BlockingQueue<Integer> queue;
        public Receiver(DataInputStream in,BlockingQueue<Integer> q)  {
            this.queue = q;
            this.in = in;
        }

        /**
         * runner will loop in while true, commands to execute by server come with '/' at the start, else is just
         * a print. commands from server are handled in 'fromserverHandler' is true for exit or reconnect
         */
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
                } catch (IOException e) {
                    try {
                        queue.put(2);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                    break;
                }

            }

        }

        /**
         * file downlaods are made here
         * @param fileName path where file and file is stored
         */
        private void receiveFile(String fileName, int port){
            try {
                Socket s = new Socket(serverAddress, port);

                DataInputStream in = new DataInputStream(s.getInputStream());
                int bytes = 0;
                FileOutputStream fileOutputStream = new FileOutputStream(fileName);

                long size = in.readLong();     // read file size
                byte[] buffer = new byte[4 * 1024];
                while (size > 0 && (bytes = in.read(buffer, 0, (int) Math.min(buffer.length, size))) != -1) {
                    fileOutputStream.write(buffer, 0, bytes);
                    size -= bytes;      // read upto file size
                }
                fileOutputStream.close();
                s.close();
            } catch (IOException e) {
                // an error on download server crached
                // call delete function
            }
        }

        /**
         * server commands receveid to be executed by client
         * @param command command
         * @return true to break recv, false if no thread action is needed
         */
        public boolean fromServerHandler(String command){
            if (command.equals("/reconnect")){
                reconnect = 1;
                try {
                    queue.put(1);
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
                            receiveFile(BASE_DIR + info[1],Integer.parseInt(info[2]));
                        } else {
                            System.out.println("local: an error ocurred while creating folder");
                        }
                    }
                    else {
                        receiveFile(BASE_DIR + info[1],Integer.parseInt(info[2]));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return false;
        }
    }

}

