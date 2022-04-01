import java.net.*;
import java.io.*;
import java.nio.file.Files;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Server{

    /**
     * main funtion where connection to clients are made
     * get all configuration from config.txt using getconfig
     * all servers start at default 'second server' where its determined if they are meant to be kept like that or start
     * as main server. start hearbeat, backup, rmi, client listening
     * @param args not used
     */
    public static void main(String[] args){
        // delete this
        if(args.length!=1){
            System.out.println("USAGE: java server hierachy ( where hierachy is 1 or 2 )");
            return;
        }

        int deletethis = Integer.parseInt(args[0]); // only used for reading config file on same directory delete on final
        FileAccess FA = new FileAccess();
        ArrayList<String> config = FA.getconfig(deletethis); // delete parameter
        String serverAddress = config.get(0);
        int serverPort = Integer.parseInt(config.get(1));
        int HBPort = Integer.parseInt(config.get(2));
        int BUPort = Integer.parseInt(config.get(3));
        String otherip = config.get(4);
        int otherHBPort = Integer.parseInt(config.get(5));
        int otherBUPort = Integer.parseInt(config.get(6));
        int HBdelay = Integer.parseInt(config.get(7));
        int HBmax = Integer.parseInt(config.get(8));
        String serverHierachy = config.get(9);


        try {
        HeartBeat HB = new HeartBeat(false,InetAddress.getByName(otherip),otherHBPort,HBPort,HBmax,HBdelay,serverHierachy);
        ConnectionUDP backup = new ConnectionUDP(2,otherip,otherBUPort,BUPort,HB);
        HB.start();
        backup.start();
        HB.join();
        backup.join();
        } catch (InterruptedException | UnknownHostException e) {
            e.printStackTrace();
        }


        try {

            ServerSocket listenSocket = new ServerSocket();
            SocketAddress sockaddr = new InetSocketAddress(serverAddress, serverPort);
            listenSocket.bind(sockaddr);

            BlockingQueue<String> filequeue = new LinkedBlockingQueue<>();
            HeartBeat HB = new HeartBeat(true,InetAddress.getByName(otherip),otherHBPort,HBPort,HBmax,HBdelay,serverHierachy);
            HB.start();
            ConnectionUDP backup = new ConnectionUDP(1,otherip,otherBUPort,BUPort,filequeue);
            backup.start();

            System.out.println("DEBUG: Server started at port " + serverPort + " with socket " + listenSocket);
            AdminConsole rmi = new AdminConsole();
            try {
                Registry r = LocateRegistry.createRegistry(7001);
                r.rebind("admin", rmi);

                System.out.println("DEGUB: RMI port enabled");
            }
            catch (RemoteException re) {
                System.out.println("DEBUG: an error occurred while enabling RMI port: " + re);
            }
            while(true) {
                Socket clientSocket = listenSocket.accept(); // BLOQUEANTE
                System.out.println("DEBUG: Client connected, clientsocket = "+clientSocket);
                new Connection(clientSocket, FA, filequeue);
            }
        } catch(IOException e) {
            e.printStackTrace();
            System.out.println("Listen: " + e.getMessage());
        }
    }
}

class HeartBeat extends Thread{

    private DatagramPacket request, reply;
    private DatagramSocket socket;
    private static byte[] buffer = new byte[1];
    private boolean primary;
    private static String message;
    public static int hb_cont;
    public static int hb_default;
    public static int delay;

    public HeartBeat(boolean hierarchy,InetAddress otherip,int otherport,int port, int cont, int d, String m){

        try {
            socket = new DatagramSocket(port);
            message = m;
            buffer = message.getBytes();
            reply = new DatagramPacket(buffer, buffer.length, otherip, otherport);
            request = new DatagramPacket(buffer, buffer.length);
            primary = hierarchy;
            hb_cont = 5; // read from config file
            hb_default = hb_cont;
            delay = d;

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * function executed by primary server, resends what it recevies
     */
    public void ackping(){

        while (true) {
            try {
                socket.receive(request);
                //System.out.println("recebi do failover");
                socket.send(reply);
            } catch (IOException e) {
                e.printStackTrace();
                socket.close();
            }

        }

    }

    /**
     * runner will start either ack or threads to send and receive, depending on the server being main or second
     */
    public void run(){
        if(primary) {
            ackping();
        }
        else{
            PingRecv pingrecv = new PingRecv(request,socket,delay,hb_cont);
            pingrecv.start();
            PingSend pingsend = new PingSend(reply,socket,delay);
            pingsend.start();

            try {
                pingrecv.join();
                pingsend.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            socket.close();
        }

    }

    /**
     * class to receive pings
     */
    public static class PingRecv extends Thread{

        private DatagramPacket request;
        private DatagramSocket socket;
        private int delay;
        private int max;

        public PingRecv( DatagramPacket r, DatagramSocket s, int d, int m){
            request = r;
            socket = s;
            delay = d;
            max = m;
        }

        /**
         * runner will have a socket oponed for as long has the max number of pings to become main is set.
         * After this delay will see if the counter is at 0, if it is it breaks loop. if it receives ack
         * it resets counter.
         * because default is secondary server, there will be two scenarios, either the server pings n times and the other
         * doest respond and it becomes main, or they are started at the same time, to determine wich server should become
         * main the one with the higher hierachy wins
         */
        public void run(){

            while(true){
                try {
                    socket.setSoTimeout(delay*max); // equals the amount of time to send all ack iteration
                    hb_cont = hb_default;
                    socket.receive(request);
                    int r = Integer.parseInt(new String(request.getData(), 0, request.getLength()));
                    if(r < Integer.parseInt(message)){ // 2 < 1
                        break;
                    }
                    if(hb_cont<0){
                        break;
                    }
                }
                catch (SocketTimeoutException e){
                    break;
                }
                catch (IOException e) {
                    e.printStackTrace();
                }

            }

        }

    }

    /**
     * class that sends pings
     */
    public static class PingSend extends Thread{

        private DatagramPacket reply;
        private DatagramSocket socket;
        private int delay;

        public PingSend(DatagramPacket r,DatagramSocket s, int d){
            delay = d;
            socket = s;
            reply = r;
        }

        /**
         * runner sends pings delay by some time, and decrements counter
         */
        public void run(){

            while(true){
                try {
                    socket.send(reply);
                    //System.out.println("mandei");
                    hb_cont--;
                    Thread.sleep(delay);
                    if(hb_cont<0){
                        break;
                    }

                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }

            }

        }

    }

}

class FileAccess {

    /**
     * get user information from clients.txt
     * @param username username of client
     * @return arrayslist of string will all info
     */
    public synchronized ArrayList<String> getUserInfo(String username) {

        ArrayList<String> userinfo = new ArrayList<>();
        try {
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients.txt");
            Scanner reader = new Scanner(file);
            while (reader.hasNextLine()) {
                String user = reader.nextLine();
                String[] info = user.split(" / ");
                if (info[0].equals(username)) {
                    userinfo.add(info[0]); // username
                    userinfo.add(info[1]); // pass
                    userinfo.add(info[2]); // last dir
                    System.out.printf("DEBUG: Username: %s | Password: %s\n", userinfo.get(0), userinfo.get(1));
                }
            }
            reader.close();
        } catch (FileNotFoundException e) {
            System.out.println("DEBUG: File not found.");
            e.printStackTrace();
        }
        return userinfo;
    }

    /**
     * changes users password
     * @param username users username
     * @param newPassword new password
     * @return true if change was succefull
     */
    public synchronized boolean changePassword(String username, String newPassword) {
        // ver se a password é valida?
        ArrayList<String> lines = new ArrayList<>();
        boolean changed = false;
        try {
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients.txt");
            Scanner reader = new Scanner(file);
            while (reader.hasNextLine()) {
                String user = reader.nextLine();
                String[] info = user.split(" / ");
                if (info[0].equals(username)) {
                    user = info[0] + " / " + newPassword + " / " + info[2];
                    changed = true;
                }
                lines.add(user);
            }
            reader.close();

            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/clients.txt");
            for (String s: lines) {
                fileWriter.write(s + "\n");
            }
            fileWriter.close();

        } catch (FileNotFoundException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return changed;
    }

    /**
     * save users current directory
     * @param username username of client
     * @param currentDir directory
     * @return true if it was succefull
     */
    public synchronized boolean saveCurrentDir(String username,String currentDir){
        ArrayList<String> lines = new ArrayList<>();
        boolean changed = false;
        try {
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients.txt");
            Scanner reader = new Scanner(file);
            while (reader.hasNextLine()) {
                String user = reader.nextLine();
                String[] info = user.split(" / ");
                if (info[0].equals(username)) {
                    user = info[0] + " / " + info[1] + " / " + currentDir;
                    changed = true;
                }
                lines.add(user);
            }
            reader.close();

            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/clients.txt");
            for (String s: lines) {
                fileWriter.write(s + "\n");
            }
            fileWriter.close();

        } catch (FileNotFoundException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return changed;
    }

    /**
     * get server configuration ip, port etc
     * @param a delete this
     * @return arraylist will all information
     */
    public synchronized ArrayList<String> getconfig(int a){
        // delete all this
        String s;
        if(a==1){
            s = "/home/config.txt";
        }
        else{
            s = "/home/otherconfig.txt";
        }

        ArrayList<String> config = new ArrayList<>();
        try {
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + s);
            Scanner reader = new Scanner(file);
            while (reader.hasNextLine()) {
                String line = reader.nextLine();
                if(!line.startsWith("// ")){
                    String[] values = line.split(": ");
                    if(values.length==2){
                        config.add(values[1]);
                    }
                }

            }
            reader.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return config;
    }

}

class Connection extends Thread {
    private DataInputStream in;
    private DataOutputStream out;
    private Socket clientSocket;
    private String Username;
    private String currentDir;
    private FileAccess fa;
    private BlockingQueue<String> fq;

    public Connection (Socket aClientSocket, FileAccess FA,BlockingQueue<String> f) {
        try{
            clientSocket = aClientSocket;
            in = new DataInputStream(clientSocket.getInputStream());
            out = new DataOutputStream(clientSocket.getOutputStream());
            fa = FA;
            fq = f;
            this.start();
        }catch(IOException e){System.out.println("DEBUG: Connection: " + e.getMessage());}
    }

    /**
     * runner will be stuck at login until it is confirmed, then enters loop where all commands are handled.
     * exit or reconnect will break loop
     */
    public void run(){
        try {
            login();

            String response = "server /" + currentDir + " > ";
            String fromClient;
            while(true){
                out.writeUTF(response);
                if(response.equals("/reconnect") || response.equals("/exit")){
                    break;
                }
                fromClient = in.readUTF();
                System.out.println("DEBUG: from client: "+fromClient);
                response = commandHandler(fromClient);

            }

            clientSocket.close();
            in.close();
            out.close();
            if(!fa.saveCurrentDir(Username, currentDir)){
                System.out.println("DEBUG: Error saving directory");
            }

            System.out.println("DEBUG: Client " + clientSocket +" left");

        } catch(EOFException e) {
            System.out.println("EOF:" + e);
        } catch(IOException e) {
            System.out.println("IO:" + e);
        }
    }

    /**
     * retriveis users username and password, stuck in here until it succedes
     */
    public void login(){

        try{

            ArrayList<String> contents = new ArrayList<>();
            boolean foundUsername = false;
            boolean passwordValid = false;

            while(!foundUsername) {
                out.writeUTF("server - insert username: ");
                contents = fa.getUserInfo(in.readUTF());
                if(contents.size()==0)
                    out.writeUTF("server - Username not found\n");
                else {
                    Username = contents.get(0);
                    foundUsername = true;
                }

            }
            while(!passwordValid) {
                out.writeUTF("server - insert password: ");
                if (in.readUTF().equals(contents.get(1)))
                    passwordValid=true;
                else
                    out.writeUTF("server - wrong password\n");
            }

            currentDir = contents.get(2);
            String BASE_DIR = System.getProperty("user.dir");
            File directory = new File(BASE_DIR + "/home/" + currentDir);
            if (!directory.exists()){
                System.out.println("DEBUG: " + BASE_DIR + "/home/" + currentDir);
                if (directory.mkdirs()) {
                    System.out.println("DEBUG: Directory has been created successfully");
                }
                else {
                    System.out.println("DEBUG: Directory cannot be created");
                }
            }
            else
                System.out.println("DEBUG: Directory already exists");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * new password handler
     * @return true if all went right
     */
    public boolean newPasswordRequest() {
        try {
            out.writeUTF("server - new password: ");
            String newpass = in.readUTF();
            if(fa.changePassword(Username, newpass))
                return true;

        }
        catch(IOException e) {
            System.out.println("IO:" + e);
        }
        return false;
    }

    /**
     * send file to client
     * @param path path where file is
     */
    public void sendFile(String path, ServerSocket s){
        try {
            Socket cs = s.accept(); // BLOQUEANTE waiting for client
            System.out.println("DEBUG: connected file transfer socket: " + s);
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
            System.out.println("DEBUG: client disconnected, download failed");
        }
    }

    /**
     * receives file from client
     * @param fileName name of the file
     */
    private void receiveFile(String fileName, int port){
        try {
            Socket s = new Socket(clientSocket.getInetAddress(), port);
            System.out.println("DEBUG: connected file transfer socket: " + s);

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
            // an error occurred while receiving file delete
            // call delete funcion
        }

    }

    /**
     * handles all comands received from client
     * @param command command to be handled
     * @return text to be displayed in client console or a command to be executed on client side
     */
    public String commandHandler(String command) {
        try {

            if(command.equals("help")){
                String commands = "";;
                commands += "\n\trp - reset password\n";
                commands += "\texit - leave server\n";
                commands += "\tcd - home directory\n";
                commands += "\tcd .. - previous directory\n";
                commands += "\tcd dir - go to dir directory\n";
                commands += "\tmkdir dir - create dir directory\n";
                return commands + "\nserver /" + currentDir + " > ";
            }

            else if(command.equals("server")){
                return "server /" + currentDir + " > ";
            }

            else if(command.equals("rp")){
                if(newPasswordRequest()) {
                    return "/reconnect";
                }
                else{
                    out.writeUTF("server - an error as ocorrued\nserver /" + currentDir + " > ");
                }
            }

            else if(command.equals("exit")){
                return "/exit";
            }

            else if(command.startsWith("cd")){
                if(command.equals("cd")){
                    currentDir = Username + "/home";
                    fa.saveCurrentDir(Username,currentDir);
                    return "server /" + currentDir + " > ";
                }
                else{
                    String nextCommand = command.substring(2);
                    if(nextCommand.equals(" ..")){
                        if(currentDir.equals(Username + "/home")){
                            fa.saveCurrentDir(Username,currentDir);
                            return "server /" + currentDir + " >";
                        }
                        currentDir = currentDir.substring(0,currentDir.lastIndexOf("/"));
                        fa.saveCurrentDir(Username,currentDir);
                        return "server /" + currentDir + " > " ;
                    }
                    if(nextCommand.charAt(0) == ' '){
                        String nextDir = nextCommand.substring(1);
                        String BASE_DIR = System.getProperty("user.dir");
                        File directory = new File(BASE_DIR + "/home/" + currentDir + "/" + nextDir);
                        if (directory.exists()) {
                            currentDir = currentDir + "/" + nextDir;
                            fa.saveCurrentDir(Username,currentDir);
                            return "server /" + currentDir + " > " ;
                        }
                        else
                            return "server - folder doesn't exist\nserver /"  + currentDir + " > ";
                    }
                }

            }

            else if (command.startsWith("mkdir ")) {
                String newFolder = command.substring(6);
                String BASE_DIR = System.getProperty("user.dir");
                File directory = new File(BASE_DIR + "/home/" + currentDir + "/" + newFolder);
                if (!directory.exists()){
                    System.out.println("DEBUG: " + BASE_DIR + "/home/" + currentDir);
                    if (directory.mkdirs()) {
                        System.out.println("DEBUG: Directory has been created successfully");
                        currentDir = currentDir + "/" + newFolder;

                        String aux = "/home/"  + currentDir;
                        System.out.println("DEBUG: placing - " + aux +" in queue");
                        fq.put("Folder");
                        fq.put(aux);

                        return "server /" + currentDir + " > " ;
                    }
                    else {
                        System.out.println("DEBUG: Directory cannot be created");
                    }
                }
                else {
                    System.out.println("DEBUG: Directory already exists");
                    currentDir = currentDir + "/" + newFolder;
                    return "server /" + currentDir + " > ";
                }
            }

            else if(command.equals("ls")){
                String BASE_DIR = System.getProperty("user.dir");
                File directory = new File(BASE_DIR + "/home/" + currentDir);

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
                output.append("server /").append(currentDir).append(" > ");

                return output.toString();
            }

            else if(command.startsWith("download ")){
                String[] info = command.split(" ");
                String BASE_DIR = System.getProperty("user.dir");
                File directory = new File(BASE_DIR + "/home/" + currentDir + "/" + info[1]);
                if (!directory.exists()){
                    return "server: file does not exist" +
                            "\nserver /" + currentDir + " > ";
                }
                ServerSocket s = new ServerSocket(0); // get available port
                out.writeUTF("/file_download "+info[2]+info[1]+ " " + s.getLocalPort());

                String filepath = BASE_DIR + "/home/" + currentDir +"/"+ info[1];
                sendFile(filepath,s);
                return "server: download complete\nserver /" + currentDir + " > ";
            }

            else if(command.startsWith("upload ")) {
                String[] info = command.split(" ");
                String BASE_DIR = System.getProperty("user.dir");
                String dir = info[1].substring(0, info[1].lastIndexOf("/")); // get dir until file
                File directory = new File(BASE_DIR +"/home/"+ Username + dir);
                if (!directory.exists()) {
                    if (directory.mkdirs()) {
                        receiveFile(BASE_DIR +"/home/"+ Username + info[1], Integer.parseInt(info[2]));
                    } else {
                        System.out.println("DEBUG: an error ocurred while creating folder");
                    }
                } else {
                    receiveFile(BASE_DIR +"/home/"+ Username + info[1], Integer.parseInt(info[2]));
                }
                String aux = "/home/"+ Username + info[1];
                System.out.println("DEBUG: placing - " + aux +" in queue");
                fq.put("File");
                fq.put(aux);
                return "";
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return "server: invalid command\nserver /" + currentDir + " > ";
    }
}

class ConnectionUDP extends Thread {
    private int serverHierarchy;
    private String address;
    private int port;
    private int ourport;
    private BlockingQueue<String> fq;
    private HeartBeat HB;

    public ConnectionUDP(int serverHierarchy, String address, int port,int otherport,BlockingQueue<String> filequeue){
        this.serverHierarchy = serverHierarchy;
        this.address = address;
        this.port = port;
        this.ourport = otherport;
        this.fq = filequeue;
    }
    public ConnectionUDP(int serverHierarchy, String address, int port,int otherport, HeartBeat hb){
        this.serverHierarchy = serverHierarchy;
        this.address = address;
        this.port = port;
        this.ourport = otherport;
        this.HB = hb;
    }

    /**
     * runner identifies the hierarchy of the server, so it can be the sender (primary) or the receiver (secondary) of
     * backup files
     */
    public void run() {
        if (serverHierarchy == 1) {
            //send
            while (true) {
                try {
                    String type = fq.take();
                    String path = fq.take();
                    System.out.println("DEBUG: saving file: " + path + " on second server");
                    sendFileUDP(type, path);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        else {
            //receive
            boolean run = true;

            while (run) {
                run = receiveFileUDP();
            }
        }
    }

    /**
     * reads a file and converts it to a byte array
     * @param file file to be read
     * @return byte array of the file
     */
    public static byte[] readFileToByteArray(File file) {
        byte[] b = new byte[(int) file.length()];
        try {
            FileInputStream fis = new FileInputStream(file);
            fis.read(b);
            fis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return b;
    }

    /**
     * sends a file or directory over UDP
     * @param fileType type of the file (file or folder)
     * @param filePath path of the file
     */
    public void sendFileUDP(String fileType, String filePath) {
        System.out.println("DEBUG: Backup Started");
        String type_path = fileType + '_' + filePath;

        try {
            DatagramSocket socket = new DatagramSocket(ourport);

            // Send Type and Path
            boolean checkTypePath;
            byte[] fileTypePathBytes = type_path.getBytes();
            byte[] fileTP = new byte[fileTypePathBytes.length + 1];
            fileTP[0] = (byte) (1);
            System.arraycopy(fileTypePathBytes, 0, fileTP, 1, fileTypePathBytes.length);
            DatagramPacket filePathPacket = new DatagramPacket(fileTP, fileTP.length, InetAddress.getByName(address), port);
            socket.send(filePathPacket);
            System.out.printf("DEBUG: Sent: path %s\n", new String(fileTP, 1, fileTP.length - 1));
            // Know if the path was received correctly
            while (true) {
                byte[] check = new byte[1];
                DatagramPacket checkPacket = new DatagramPacket(check, check.length);

                try {
                    socket.setSoTimeout(500);
                    socket.receive(checkPacket);
                    checkTypePath = true;
                } catch (SocketTimeoutException e) {
                    System.out.println("DEBUG: Socket timed out waiting for acknowledgement");
                    checkTypePath = false;
                }

                if ((check[0] == fileTP[0]) && (checkTypePath)) {
                    System.out.println("DEBUG: Acknowledgement received: sequence number = " + check[0]);
                    break;
                }
                else {
                    socket.send(filePathPacket);
                    System.out.println("DEBUG: Resending: sequence number = " + check[0]);
                }
            }

            if (fileType.equals("File")) {
                // Send File
                File f = new File(System.getProperty("user.dir") + filePath);
                byte[] fileBytes = readFileToByteArray(f);

                int sequenceNumber = 0; // For order
                boolean eofFlag; // To see if EOF was reached
                int ackSequence = 0; // To see if the datagram was received correctly
                for (int i = 0; i < fileBytes.length; i = i + 1021) {
                    // Send part of the file
                    sequenceNumber += 1;

                    byte[] data = new byte[1024]; // First byte: integrety; Second Byte: order; Third Byte: EOF
                    data[0] = (byte) (sequenceNumber >> 8);
                    data[1] = (byte) (sequenceNumber);

                    if ((i + 1021) >= fileBytes.length) {
                        eofFlag = true;
                        data[2] = (byte) (1);
                    } else {
                        eofFlag = false;
                        data[2] = (byte) (0);
                    }

                    if (!eofFlag) {
                        System.arraycopy(fileBytes, i, data, 3, 1021);
                    } else {
                        System.arraycopy(fileBytes, i, data, 3, fileBytes.length - i);
                    }

                    DatagramPacket sendPacket = new DatagramPacket(data, data.length, InetAddress.getByName(address), port);
                    socket.send(sendPacket);
                    System.out.println("DEBUG: Sent: sequence number = " + sequenceNumber);

                    // Know if the part of the file was received correctly
                    boolean checkReceived;
                    while (true) {
                        byte[] check = new byte[2];
                        DatagramPacket checkPacket = new DatagramPacket(check, check.length);

                        try {
                            socket.setSoTimeout(500);
                            socket.receive(checkPacket);
                            ackSequence = ((check[0] & 0xff) << 8) + (check[1] & 0xff);
                            checkReceived = true;
                        } catch (SocketTimeoutException e) {
                            System.out.println("DEBUG: Socket timed out waiting for acknowledgement");
                            checkReceived = false;
                        }

                        if ((ackSequence == sequenceNumber) && (checkReceived)) {
                            System.out.println("DEBUG: Acknowledgement received: sequence number = " + ackSequence);
                            break;
                        }
                        else {
                            socket.send(sendPacket);
                            System.out.println("DEBUG: Resending: sequence number = " + sequenceNumber);
                        }
                    }
                }
            }

            socket.close();
        } catch (SocketException e) {
            System.out.println("DEBUG: Socket UDP Sender: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }

    /**
     * receives a file or directory over UDP
     * @return meant to be true until heartbeat is dead and server needs to become primary
     */
    public boolean receiveFileUDP() {
        System.out.println("DEBUG: Backup Started");

        try {
            DatagramSocket socket = new DatagramSocket(ourport);

            // Receive Path
            byte[] data;
            DatagramPacket fileTypePathPacket;
            boolean dataFlag = false;
            while (true) {
                byte[] fileTypePathBytes = new byte[1024];
                fileTypePathPacket = new DatagramPacket(fileTypePathBytes, fileTypePathBytes.length);

                while (true) {
                    try {
                        socket.setSoTimeout(HB.delay*2);
                        socket.receive(fileTypePathPacket);
                        break;
                    } catch (SocketTimeoutException e) {
                        if (!HB.isAlive()) {
                            System.out.println("DEBUG: Closing Backup, HeartBeat has terminated");
                            socket.close();
                            return false;
                        }
                    }
                }

                data = fileTypePathPacket.getData();

                // Send confirmation
                byte[] checkTypePath = new byte[1];
                checkTypePath[0] = data[0];
                DatagramPacket ackPathPacket = new DatagramPacket(checkTypePath, checkTypePath.length, InetAddress.getByName(address), port);
                socket.send(ackPathPacket);
                System.out.println("DEBUG: Sent path acknowledgement: sequence number = " + checkTypePath[0]);

                if (checkTypePath[0] == (byte) (1))
                    dataFlag = true;

                if (dataFlag) {
                    break;
                }
            }


            String fileTypePath = new String(data, 1, fileTypePathPacket.getLength());
            System.out.println("DEBUG: Type and Path received: " + fileTypePath);
            String fileType = fileTypePath.substring(0, fileTypePath.indexOf("_"));
            String filePath = fileTypePath.substring(fileTypePath.indexOf("_")+1);

            if (fileType.equals("File")) {
                // Receive File
                String a = filePath.substring(0, filePath.lastIndexOf("/")) + "/aaa.png"; // get dir until file
                System.out.println("DEBUG: Opening " + System.getProperty("user.dir") + a);
                File f = new File(System.getProperty("user.dir") + a);
                FileOutputStream fos = new FileOutputStream(f);

                int sequenceNumber = 0; // For order
                boolean eofFlag; // To see if EOF was reached
                int foundLast = 0; // To see the last sequence found

                while (true) {
                    byte[] message = new byte[1024];
                    byte[] fileByteArray = new byte[1021];

                    DatagramPacket receivedPacket = new DatagramPacket(message, message.length);

                    while (true) {
                        try {
                            socket.setSoTimeout(HB.delay*2);
                            socket.receive(receivedPacket);
                            break;
                        } catch (SocketTimeoutException e) {
                            if (!HB.isAlive()) {
                                System.out.println("DEBUG: Closing Backup, HeartBeat has terminated");
                                socket.close();
                                return false;
                            }
                        }
                    }

                    message = receivedPacket.getData();

                    sequenceNumber = ((message[0] & 0xff) << 8) + (message[1] & 0xff);
                    eofFlag = (message[2] & 0xff) == 1;

                    if (sequenceNumber == (foundLast + 1)) {
                        foundLast = sequenceNumber;

                        System.arraycopy(message, 3, fileByteArray, 0, 1021);

                        fos.write(fileByteArray);
                        System.out.println("DEBUG: Received acknowledgement: sequence number: " + foundLast);
                    } else {
                        System.out.println("DEBUG: Expected sequence number: " + (foundLast + 1) + " but received " + sequenceNumber + ", discarding this packet");
                    }
                    // Send confirmation
                    byte[] checkPacket = new byte[2];
                    checkPacket[0] = (byte) (foundLast >> 8);
                    checkPacket[1] = (byte) (foundLast);
                    DatagramPacket acknowledgement = new DatagramPacket(checkPacket, checkPacket.length, InetAddress.getByName(address), port);
                    socket.send(acknowledgement);
                    System.out.println("DEBUG: Sent acknowledgement: sequence number = " + foundLast);

                    if (eofFlag) {
                        fos.close();
                        break;
                    }
                }
            }

            else if (fileType.equals("Folder")) {
                String a = filePath.substring(0, filePath.lastIndexOf("/")) + "/aaa";
                File directory = new File(System.getProperty("user.dir") + a);
                if (!directory.exists()){
                    System.out.println("DEBUG: " + System.getProperty("user.dir") + a);
                    if (directory.mkdirs()) {
                        System.out.println("DEBUG: Directory has been created successfully");
                    }
                    else {
                        System.out.println("DEBUG: Directory cannot be created");
                    }
                }
                else {
                    System.out.println("DEBUG: Directory already exists");
                }
            }

            socket.close();
        } catch (SocketException e) {
            System.out.println("DEBUG: Socket UDP Receiver: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }

        return true;
    }
}

class AdminConsole extends UnicastRemoteObject implements AdminInterface{
    private static final long serialVersionUID = 1L;

    public AdminConsole() throws RemoteException {
        super();
    }

    public String registerClient(String BASE_DIR, String[] info){
        ArrayList<String> lines = new ArrayList<>();

        if (info.length > 3) return "Too many arguments";
        else if (info.length < 3) return "Not enough arguments";
        try {
            Scanner fileReader = new Scanner(new File(BASE_DIR + "/home/clients.txt"));
            while (fileReader.hasNextLine()) {
                String line = fileReader.nextLine();
                lines.add(line);
                String[] splitLine = line.split(" / ");

                if (Objects.equals(splitLine[0], info[1]))
                    return "Client already exists";
            }
            fileReader.close();

            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/clients.txt");
            for (String s: lines) fileWriter.write(s + "\n");
            fileWriter.write(String.format("%s / %s / %s/home\n", info[1], info[2], info[1]));
            fileWriter.close();
            if (!new File(BASE_DIR + "/home/" + info[1] + "/home").mkdirs())
                System.out.println("DEBUG: Error creating client folder");
        }
        catch (Exception e){
            e.printStackTrace();
        }

        return "New client registered";
    }

    private static void fileTree(File folder, int indent, StringBuilder string) throws IOException {
        File[] files = Objects.requireNonNull(folder.listFiles());
        int count = 0;

        for (File file : files) {
            count++;
            if (file.isDirectory()) {
                string.append(String.join("", Collections.nCopies(indent, "│  ")));
                if (!Files.newDirectoryStream(file.toPath()).iterator().hasNext() && count == files.length)
                    string.append("└──");
                else
                    string.append("├──");
                string.append(file.getName()).append("/\n");
                fileTree(file, indent + 1, string);
            } else {
                string.append(String.join("", Collections.nCopies(indent, "│  ")));
                if (count != files.length)
                    string.append("├──");
                else
                    string.append("└──");
                string.append(file.getName()).append("\n");
            }
        }
    }

    public static String clientTree(String BASE_DIR, String[] info){
        if (info.length > 2) return "Too many arguments";
        else if (info.length < 2) return "Not enough arguments";

        StringBuilder string = new StringBuilder(info[1] + "/\n");
        try {
            fileTree(new File(BASE_DIR + "/home/" + info[1] + "/"), 0, string);
        }
        catch (Exception e){
            return "Client " + info[1] + " doesn't exist";
        }
        return string.toString();
    }

    public static String configServer(String BASE_DIR, String[] info){
        if (info.length > 3) return "Too many arguments";
        else if (info.length < 3) return "Not enough arguments";

        try {
            ArrayList<String> lines = new ArrayList<>();
            Scanner fileReader = new Scanner(new File(BASE_DIR + "/home/config.txt"));

            while (fileReader.hasNextLine()){
                String line = fileReader.nextLine();

                String[] splitServerLine = line.split(": ");
                if (Objects.equals(splitServerLine[0], "DELAY"))
                    lines.add("DELAY: " + info[1]);
                else if (Objects.equals(splitServerLine[0], "MAX FAILED"))
                    lines.add("MAX FAILED: " + info[2]);
                else
                    lines.add(line);
            }
            fileReader.close();

            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/config.txt");
            for (String s: lines) fileWriter.write(s + "\n");
            fileWriter.close();
        }
        catch (FileNotFoundException e){
            return "Configuration files not found";
        } catch (Exception e) {
            return "Error while writing to file";
        }

        return "Configuration file successfully edited";
    }

    private static long getFolderSize(File folder) throws NullPointerException{
        long length = 0;

        File[] files = folder.listFiles();

        for (File file : files)
            if (file.isFile())
                length += file.length();
            else
                length += getFolderSize(file);

        return length;
    }

    public String clientStorage(String BASE_DIR, String[] info){
        if (info.length > 2) return "Too many arguments";
        else if (info.length < 2) return "Not enough arguments";

        if (info[1].equals("total"))
            return "Total space occupied is: " + getFolderSize(new File(BASE_DIR + "/home/")) + " bytes";
        else
            try {
                return "Client " + info[1] + " is occupying " + getFolderSize(new File(BASE_DIR + "/home/" + info[1])) + " bytes";
            }
            catch (NullPointerException e){
                return "Client " + info[1] + " doesn't exist";
            }
    }


    public String adminCommandHandler(String command){
        String[] info = command.split(" ");
        String BASE_DIR = System.getProperty("user.dir");

        return switch (info[0]) {
            case "reg" -> registerClient(BASE_DIR, info);
            case "tree" -> clientTree(BASE_DIR, info);
            case "config" -> configServer(BASE_DIR, info);
            case "storage" -> clientStorage(BASE_DIR, info);
            default -> "Invalid command";
        };

    }
}