import java.net.*;
import java.io.*;

import java.nio.file.Files;

import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;

import java.util.*;
import java.util.concurrent.*;

public class Server{

    /**
     * > Main function where connection to clients are made;
     * > Gets all configurations from 'config.txt' using 'getconfig';
     * > All servers start at default 'second server' where it is determined if they are meant to be kept like that
     *   or start as main server;
     * > Starts Heartbeat, Backup, RMI and client listening;
     *
     * @param args are not used;
     */
    public static void main(String[] args){

        // Initialize synchronized file access object
        FileAccess FA = new FileAccess();

        // Get server configurations
        ArrayList<String> config = FA.getconfig();
        String serverAddress = config.get(0);              // Server address
        int serverPort = Integer.parseInt(config.get(1));  // Server port
        int HBPort = Integer.parseInt(config.get(2));      // Server Heartbeat port
        int BUPort = Integer.parseInt(config.get(3));      // Server Backup port

        // Get second server configurations
        String otherip = config.get(4);                    // Second server IP
        int otherHBPort = Integer.parseInt(config.get(5)); // Second server Heartbeat port
        int otherBUPort = Integer.parseInt(config.get(6)); // Second server Backup port

        // Get heartbeat configurations
        int HBdelay = Integer.parseInt(config.get(7));     // Delay
        int HBmax = Integer.parseInt(config.get(8));       // Max failed
        String serverHierachy = config.get(9);             // Hierarchy

        // Get integrity configurations
        int IPort = Integer.parseInt(config.get(10));      // Server port
        int IOtherPort = Integer.parseInt(config.get(11)); // Second server port

        // Initializing server as backup by default (if it is not the secondary it will not be stuck here)
        try {
            // Create objects
            HeartBeat HB = new HeartBeat(false, InetAddress.getByName(otherip), otherHBPort, HBPort, HBmax, HBdelay, serverHierachy);
            ConnectionUDP backup = new ConnectionUDP(2,otherip,otherBUPort,BUPort,HB,FA);
            IntegrityUDP integrity = new IntegrityUDP(otherip,IOtherPort,IPort,HB);

            // Start threads
            HB.start();
            backup.start();
            integrity.start();

            // Join threads
            HB.join();
            backup.join();
            integrity.join();
        }
        catch (InterruptedException | UnknownHostException e) {
            e.printStackTrace();
        }

        // Initializing server as primary
        try {
            // Enabling connection socket
            ServerSocket listenSocket = new ServerSocket();
            SocketAddress sockaddr = new InetSocketAddress(serverAddress, serverPort);
            listenSocket.bind(sockaddr);

            // File backup queue
            BlockingQueue<String> filequeue = new LinkedBlockingQueue<>();

            // Initializing Heartbeat
            HeartBeat HB = new HeartBeat(true, InetAddress.getByName(otherip), otherHBPort, HBPort, HBmax, HBdelay, "1");
            HB.start();

            // Initializing Backup
            ConnectionUDP backup = new ConnectionUDP(1,otherip,otherBUPort,BUPort,filequeue,FA);
            backup.start();

            System.out.println("DEBUG: Server started at port " + serverPort + " with socket " + listenSocket);

            // Enabling RMI
            AdminConsole rmi = new AdminConsole(FA, HB, otherip, IOtherPort, IPort);
            Registry r = LocateRegistry.createRegistry(7001);
            r.rebind("admin", rmi);

            System.out.println("DEGUB: RMI port enabled");

            // Loop to handle new clients
            while(true) {
                Socket clientSocket = listenSocket.accept(); // Blocking
                System.out.println("DEBUG: Client connected, clientsocket = " + clientSocket);
                new Connection(clientSocket, FA, filequeue);
            }
        }
        catch (RemoteException re) {
            System.out.println("DEBUG: an error occurred while enabling RMI port: " + re);
        }
        catch(IOException e) {
            e.printStackTrace();
            System.out.println("Listen: " + e.getMessage());
        }
    }
}

class HeartBeat extends Thread{

    private DatagramPacket request, reply;
    private DatagramSocket socket;
    private boolean primary;
    private static String message;
    public static int hb_cont;
    public static int hb_default;
    public int delay;

    public HeartBeat(boolean hierarchy, InetAddress otherip, int otherport, int port, int cont, int d, String m){
        try {
            // Getting Heartbeat socket
            this.socket = new DatagramSocket(port);

            // Message to send (hierarchy value)
            message = m;
            byte[] buffer = message.getBytes();
            this.reply = new DatagramPacket(buffer, buffer.length, otherip, otherport);

            // Message to receive
            byte[] b = new byte[1];
            this.request = new DatagramPacket(b, b.length);

            // Define hierarchy
            this.primary = hierarchy;

            // Ping counter
            hb_cont = cont;
            hb_default = hb_cont;

            // Ping delay
            delay = d;

        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * > Function executed by primary server, resends what it receives;
     */
    public void ackping(){
        // Acknowledge loop
        while (true)
            try {
                socket.receive(request);
                socket.send(reply);
            }
            catch (IOException e) {
                e.printStackTrace();
                socket.close();
            }
    }

    /**
     * > Runner will start either ack or threads to send and receive, depending on the server being primary
     *   or secondary;
     */
    public void run(){

        // Check if it is the primary server
        if(primary) ackping();
        else{
            // Receive pings
            PingRecv pingrecv = new PingRecv(this.request, this.socket, this.delay, hb_cont);
            pingrecv.start();

            // Send pings
            PingSend pingsend = new PingSend(reply,socket,delay);
            pingsend.start();

            try {
                // Join threads
                pingrecv.join();
                pingsend.join();
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }

            // Close socket
            socket.close();
        }

    }

    /**
     * > Class to receive pings;
     */
    public static class PingRecv extends Thread{

        private final DatagramPacket request;
        private final DatagramSocket socket;
        private final int delay;
        private final int max;

        public PingRecv( DatagramPacket r, DatagramSocket s, int d, int m){
            this.request = r;
            this.socket = s;
            this.delay = d;
            this.max = m;
        }

        /**
         * > Runner will have a socket opened for as long as the max number of pings to become main is set.
         *   After this delay will see if the counter is at 0, and if so, it breaks the loop.
         *   If it receives ack it resets the counter;
         * > Because the default is secondary server, there will be two scenarios, either the server pings
         *   n times with no response and it becomes main, or they are started at the same time, to determine
         *   which server should become main: the one with the higher hierarchy wins;
         */
        public void run(){

            while(true)
                try {
                    // Sets the amount of time to send all ack iterations
                    socket.setSoTimeout(delay*max);
                    hb_cont = hb_default;
                    socket.receive(request);

                    int r = Integer.parseInt(new String(request.getData(), 0, request.getLength()));
                    if(r > Integer.parseInt(message) || hb_cont < 0) break;
                }
                catch (SocketTimeoutException e){
                    break;
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
        }
    }

    /**
     * > Class that sends pings;
     */
    public static class PingSend extends Thread{

        private final DatagramPacket reply;
        private final DatagramSocket socket;
        private final int delay;

        public PingSend(DatagramPacket r, DatagramSocket s, int d){
            this.delay = d;
            this.socket = s;
            this.reply = r;
        }

        /**
         * > Runner sends pings, sleeps for some time, and decrements counter;
         */
        public void run(){

            while(true)
                try {
                    // Send reply
                    socket.send(reply);

                    // Decrement counter
                    hb_cont--;

                    // Sleep
                    Thread.sleep(delay);

                    if(hb_cont<0) break;

                }
                catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
        }
    }
}

class FileAccess {
    private boolean updateClientstxt = false;

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
     * @return arraylist will all information
     */
    public synchronized ArrayList<String> getconfig(){
        ArrayList<String> config = new ArrayList<>();
        try {
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/config.txt");
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

    /**
     * see if there is already a clients.txt
     * @return bool
     */
    public synchronized boolean isUpdateClientstxt() {
        return updateClientstxt;
    }

    /**
     * update value if clients.txt is inserted or removed of the pipe
     */
    public synchronized void setUpdateClientstxt(boolean updateClientstxt) {
        this.updateClientstxt = updateClientstxt;
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

    public synchronized String clientTree(String BASE_DIR, String[] info){
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

    public synchronized String configServer(String BASE_DIR, String[] info){
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

    public synchronized String clientStorage(String BASE_DIR, String[] info){
        if (info.length > 2) return "Too many arguments";
        else if (info.length < 1) return "Not enough arguments";

        if (info.length == 1)
            return "Total space occupied is: " + getFolderSize(new File(BASE_DIR + "/home/")) + " bytes";
        else
            try {
                return "Client " + info[1] + " is occupying " + getFolderSize(new File(BASE_DIR + "/home/" + info[1])) + " bytes";
            }
            catch (NullPointerException e){
                return "Client " + info[1] + " doesn't exist";
            }
    }
    
    public static boolean fileExists(String filePath, String address, int port, int ourport, HeartBeat HB){
        int fExists = 0;
        try {
            DatagramSocket socket = new DatagramSocket(ourport);

            // Send Path
            boolean checkPath;
            byte[] filePathBytes = filePath.getBytes();
            byte[] fileP = new byte[filePathBytes.length + 1];
            fileP[0] = (byte) (1);
            System.arraycopy(filePathBytes, 0, fileP, 1, filePathBytes.length);
            DatagramPacket filePathPacket = new DatagramPacket(fileP, fileP.length, InetAddress.getByName(address), port);
            socket.send(filePathPacket);
            System.out.printf("DEBUG: Sent: path %s\n", new String(fileP, 1, fileP.length - 1));
            // Know if the path was received correctly
            while (true) {
                byte[] check = new byte[1];
                DatagramPacket checkPacket = new DatagramPacket(check, check.length);

                try {
                    socket.setSoTimeout(500);
                    socket.receive(checkPacket);
                    checkPath = true;
                } catch (SocketTimeoutException e) {
                    System.out.println("DEBUG: Socket timed out waiting for acknowledgement");
                    checkPath = false;
                }

                if ((check[0] == fileP[0]) && (checkPath)) {
                    System.out.println("DEBUG: Acknowledgement received: sequence number = " + check[0]);
                    break;
                } else {
                    socket.send(filePathPacket);
                    System.out.println("DEBUG: Resending: sequence number = " + check[0]);
                }
            }

            // Receive if file exists or not
            byte[] data = new byte[1];
            DatagramPacket existsPacket;
            boolean dataFlag = false;
            while (true) {
                existsPacket = new DatagramPacket(data, data.length);

                while (true) {
                    try {
                        socket.setSoTimeout(HB.delay*2);
                        socket.receive(existsPacket);
                        break;
                    } catch (SocketTimeoutException e) {
                        if (!HB.isAlive()) {
                            System.out.println("DEBUG: Closing Backup, HeartBeat has terminated");
                            socket.close();
                            return false;
                        }
                    }
                }
                fExists = data[0];

                // Send confirmation
                byte[] checkExist = new byte[1];
                checkExist[0] = data[0];
                DatagramPacket ackExistsPacket = new DatagramPacket(checkExist, checkExist.length, InetAddress.getByName(address), port);
                socket.send(ackExistsPacket);
                System.out.println("DEBUG: Sent type/path acknowledgement: sequence number = " + checkExist[0]);

                if (checkExist[0] == (byte) (1) || checkExist[0] == (byte) (0))
                    dataFlag = true;

                if (dataFlag) {
                    break;
                }
            }

            socket.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println(fExists);
        return fExists == 1;
    }

    private static void integrityTree(boolean isValid, File folder, int indent, StringBuilder string, HeartBeat hb, String ipAddress, int otherPort, int port) throws IOException {
        File[] files = Objects.requireNonNull(folder.listFiles());

        for (File file : files) {
            if (file.isDirectory()) {
                string.append(String.join("", Collections.nCopies(indent, "|  ")));
                string.append("+--");
                string.append(file.getName()).append("/");
                if (!isValid) {
                    string.append(" X\n");
                    integrityTree(false, file, indent + 1, string, hb, ipAddress, otherPort, port);
                }
                else
                    if (!fileExists(file.getPath(), ipAddress, otherPort, port, hb)) {
                        string.append(" X\n");
                        integrityTree(true, file, indent + 1, string, hb, ipAddress, otherPort, port);
                    }
            }
            else {
                string.append(String.join("", Collections.nCopies(indent, "|  ")));
                string.append("+--");

                if (!isValid){
                    string.append(file.getName()).append(" X\n");
                }
                else {
                    if (!fileExists(file.getPath(), ipAddress, otherPort, port, hb)) {
                        string.append(file.getName()).append(" X\n");
                    }
                }
            }
        }
    }

    public synchronized String checkIntegrity(String BASE_DIR, String[] info, HeartBeat hb, String ipAddress, int otherPort, int port){
        if (info.length > 1) return "Too many arguments";
        else if (info.length < 1) return "Not enough arguments";

        StringBuilder string = new StringBuilder("home ✔/\n");
        try {
            integrityTree(true, new File(BASE_DIR + "/home/"), 0, string, hb, ipAddress, otherPort, port);
        }
        catch (Exception e){
            System.out.println("DEBUG: Error while getting integrity tree");
        }
        return string.toString();
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

                    String aux = "/home/"  + currentDir;
                    System.out.println("DEBUG: placing - " + aux +" in queue");
                    fq.put("Folder");
                    fq.put(aux);
                }
                else {
                    System.out.println("DEBUG: Directory cannot be created");
                }
            }
            else
                System.out.println("DEBUG: Directory already exists");

        } catch (IOException | InterruptedException e) {
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
            if(fa.changePassword(Username, newpass)) {
                if (!fa.isUpdateClientstxt()) {
                    fq.put("File");
                    fq.put("/home/clients.txt");
                    fa.setUpdateClientstxt(true);
                }
                return true;
            }

        }
        catch(IOException e) {
            System.out.println("IO:" + e);
        } catch (InterruptedException e) {
            e.printStackTrace();
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
            System.out.println("DEBUG: client disconnected, upload failed");
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
                String commands = "";
                commands += "\n\trp - reset password\n";
                commands += "\texit - leave server\n";
                commands += "\tls - files in current directory\n";
                commands += "\tcd - home directory\n";
                commands += "\tcd .. - previous directory\n";
                commands += "\tcd dir - go to dir directory\n";
                commands += "\tmkdir dir - create dir directory\n";
                commands += "\tdownload filename /home/path/ - download file to local path\n";
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
                    if (!fa.isUpdateClientstxt()) {
                        fq.put("File");
                        fq.put("/home/clients.txt");
                        fa.setUpdateClientstxt(true);
                    }
                    return "server /" + currentDir + " > ";
                }
                else{
                    String nextCommand = command.substring(2);
                    if(nextCommand.equals(" ..")){
                        if(currentDir.equals(Username + "/home")){
                            fa.saveCurrentDir(Username,currentDir);
                            if (!fa.isUpdateClientstxt()) {
                                fq.put("File");
                                fq.put("/home/clients.txt");
                                fa.setUpdateClientstxt(true);
                            }
                            return "server /" + currentDir + " >";
                        }
                        currentDir = currentDir.substring(0,currentDir.lastIndexOf("/"));
                        fa.saveCurrentDir(Username,currentDir);
                        if (!fa.isUpdateClientstxt()) {
                            fq.put("File");
                            fq.put("/home/clients.txt");
                            fa.setUpdateClientstxt(true);
                        }
                        return "server /" + currentDir + " > " ;
                    }
                    if(nextCommand.charAt(0) == ' '){
                        String nextDir = nextCommand.substring(1);
                        String BASE_DIR = System.getProperty("user.dir");
                        File directory = new File(BASE_DIR + "/home/" + currentDir + "/" + nextDir);
                        if (directory.exists()) {
                            currentDir = currentDir + "/" + nextDir;
                            fa.saveCurrentDir(Username,currentDir);
                            if (!fa.isUpdateClientstxt()) {
                                fq.put("File");
                                fq.put("/home/clients.txt");
                                fa.setUpdateClientstxt(true);
                            }
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
                        fa.saveCurrentDir(Username,currentDir);
                        if (!fa.isUpdateClientstxt()) {
                            fq.put("File");
                            fq.put("/home/clients.txt");
                            fa.setUpdateClientstxt(true);
                        }

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

                        String aux = "/home/"+ Username + dir;
                        System.out.println("DEBUG: placing - " + aux +" in queue");
                        fq.put("Folder");
                        fq.put(aux);
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
    private FileAccess fa;
    private BlockingQueue<String> fq;
    private HeartBeat HB;

    public ConnectionUDP(int serverHierarchy, String address, int port,int otherport,BlockingQueue<String> filequeue,FileAccess f){
        this.serverHierarchy = serverHierarchy;
        this.address = address;
        this.port = port;
        this.ourport = otherport;
        this.fq = filequeue;
        this.fa = f;
    }
    public ConnectionUDP(int serverHierarchy, String address, int port,int otherport, HeartBeat hb,FileAccess f){
        this.serverHierarchy = serverHierarchy;
        this.address = address;
        this.port = port;
        this.ourport = otherport;
        this.HB = hb;
        this.fa = f;
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
                    if (path.equals("/home/clients.txt"))
                        fa.setUpdateClientstxt(false);
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
            DatagramPacket fileTypePathPacket = new DatagramPacket(fileTP, fileTP.length, InetAddress.getByName(address), port);
            socket.send(fileTypePathPacket);
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
                    socket.send(fileTypePathPacket);
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
                DatagramPacket ackTypePathPacket = new DatagramPacket(checkTypePath, checkTypePath.length, InetAddress.getByName(address), port);
                socket.send(ackTypePathPacket);
                System.out.println("DEBUG: Sent type/path acknowledgement: sequence number = " + checkTypePath[0]);

                if (checkTypePath[0] == (byte) (1))
                    dataFlag = true;

                if (dataFlag) {
                    break;
                }
            }


            String fileTypePath = new String(data, 1, fileTypePathPacket.getLength()-1);
            System.out.println("DEBUG: Type and Path received: " + fileTypePath);
            String fileType = fileTypePath.substring(0, fileTypePath.indexOf("_"));
            String filePath = fileTypePath.substring(fileTypePath.indexOf("_")+1);

            if (fileType.equals("File")) {
                // Receive File
                System.out.println("DEBUG: Opening " + System.getProperty("user.dir") + filePath);
                File f = new File(System.getProperty("user.dir") + filePath);
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
                File directory = new File(System.getProperty("user.dir") + filePath);
                if (!directory.exists()){
                    System.out.println("DEBUG: " + System.getProperty("user.dir") + filePath);
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

class IntegrityUDP extends Thread {
    private String address;
    private int port;
    private int ourport;
    private HeartBeat HB;


    public IntegrityUDP(String address, int port, int otherport, HeartBeat hb) {
        this.address = address;
        this.port = port;
        this.ourport = otherport;
        this.HB = hb;
    }

    /**
     * runner keeps the function to check integrity running
     */
    public void run() {
        //receive
        boolean run = true;

        System.out.println("DEBUG: Opening Integrity Check");
        while (run) {
            run = checkIntegrity();
        }
        System.out.println("DEBUG: Closing Integrity Check");
    }

    /**
     * receives a path over UDP to check if it exists in secondary server
     * @return meant to be true until heartbeat is dead and server needs to become primary
     */
    public boolean checkIntegrity() {
        try {
            DatagramSocket socket = new DatagramSocket(ourport);

            // Receive Path
            byte[] data;
            DatagramPacket filePathPacket;
            boolean dataFlag = false;
            while (true) {
                byte[] filePathBytes = new byte[1024];
                filePathPacket = new DatagramPacket(filePathBytes, filePathBytes.length);

                while (true) {
                    try {
                        socket.setSoTimeout(HB.delay*2);
                        socket.receive(filePathPacket);
                        break;
                    } catch (SocketTimeoutException e) {
                        if (!HB.isAlive()) {
                            System.out.println("DEBUG: Closing Integrity Check, HeartBeat has terminated");
                            socket.close();
                            return false;
                        }
                    }
                }

                data = filePathPacket.getData();

                // Send confirmation
                byte[] checkPath = new byte[1];
                checkPath[0] = data[0];
                DatagramPacket ackPathPacket = new DatagramPacket(checkPath, checkPath.length, InetAddress.getByName(address), port);
                socket.send(ackPathPacket);
                System.out.println("DEBUG: Sent path acknowledgement: sequence number = " + checkPath[0]);

                if (checkPath[0] == (byte) (1))
                    dataFlag = true;

                if (dataFlag) {
                    break;
                }
            }


            String filePath = new String(data, 1, filePathPacket.getLength()-1);
            System.out.println("DEBUG: Path received: " + filePath);

            // Send if path exists or not
            boolean confirmationReceived;
            byte[] fileExists = new byte[1];
            File f = new File(filePath);
            if (f.exists()) {
                fileExists[0] = (byte) (1);
            }

            DatagramPacket fileExistsPacket = new DatagramPacket(fileExists, fileExists.length, InetAddress.getByName(address), port);
            socket.send(fileExistsPacket);
            System.out.println("DEBUG: Sent: file exist value = " + fileExists[0]);
            // Know if file exist value was received correctly
            while (true) {
                byte[] check = new byte[1];
                DatagramPacket checkPacket = new DatagramPacket(check, check.length);

                try {
                    socket.setSoTimeout(500);
                    socket.receive(checkPacket);
                    confirmationReceived = true;
                } catch (SocketTimeoutException e) {
                    System.out.println("DEBUG: Socket timed out waiting for acknowledgement");
                    confirmationReceived = false;
                }

                if ((check[0] == fileExists[0]) && (confirmationReceived)) {
                    System.out.println("DEBUG: Acknowledgement received: sequence number = " + check[0]);
                    break;
                }
                else {
                    socket.send(fileExistsPacket);
                    System.out.println("DEBUG: Resending: sequence number = " + check[0]);
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
    private final FileAccess fa;
    private final HeartBeat hb;
    private final String ipAddress;
    private final int port;
    private final int ourPort;

    public AdminConsole(FileAccess FA, HeartBeat HB, String ipAddress, int port, int ourPort) throws RemoteException {
        super();
        this.fa = FA;
        this.hb = HB;
        this.ipAddress = ipAddress;
        this.port = port;
        this.ourPort = ourPort;
    }

    public String adminCommandHandler(String command){
        String[] info = command.split(" ");
        String BASE_DIR = System.getProperty("user.dir");

        return switch (info[0]) {
            case "reg"     -> fa.registerClient(BASE_DIR, info);
            case "tree"    -> fa.clientTree(BASE_DIR, info);
            case "config"  -> fa.configServer(BASE_DIR, info);
            case "storage" -> fa.clientStorage(BASE_DIR, info);
            case "integrity" -> fa.checkIntegrity(BASE_DIR, info, hb, ipAddress, port, ourPort);
            case "help" -> """

                    \texit - end RMI connection
                    \treg client password - create new client
                    \ttree client - check file tree
                    \tconfig delay max_failed - edit heartbeat configurations
                    \tstorage (client) - check how much space is being used
                    \tintegrity - check what files are backed up in 2nd server
                    
                    """;
            default -> "Invalid command";
        };
    }
}