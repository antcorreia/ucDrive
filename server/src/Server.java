import java.net.*;
import java.io.*;

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
            AdminConsole rmi = new AdminConsole(FA, HB, filequeue, otherip, IOtherPort, IPort);
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
                this.reply = new DatagramPacket(request.getData(), request.getLength(), request.getAddress(), request.getPort());
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
                    // Socket is open enough time to send all ack iterations
                    socket.setSoTimeout(delay*max);
                    hb_cont = hb_default;
                    socket.receive(request);

                    int r = Integer.parseInt(new String(request.getData(), 0, request.getLength()));
                    if(r > Integer.parseInt(message) || hb_cont < 0) {
                        hb_cont = -1;
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
     * > Get user information from clients.txt;
     * @param username username of client;
     * @return arrayslist of string will all info;
     */
    public synchronized ArrayList<String> getUserInfo(String username) {
        ArrayList<String> userinfo = new ArrayList<>();

        try {
            // Initialize variables
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients.txt");
            Scanner reader = new Scanner(file);

            // Get client information
            while (reader.hasNextLine()) {
                String user = reader.nextLine();
                String[] info = user.split(" / ");
                if (info[0].equals(username)) {
                    userinfo.add(info[0]); // Username
                    userinfo.add(info[1]); // Password
                    userinfo.add(info[2]); // Last directory

                    System.out.printf("DEBUG: Username: %s | Password: %s\n", userinfo.get(0), userinfo.get(1));
                }
            }

            // Close reader
            reader.close();
        }
        catch (FileNotFoundException e) {
            System.out.println("DEBUG: File not found.");
            e.printStackTrace();
        }
        return userinfo;
    }

    /**
     * > Changes user's password;
     * @param username users username;
     * @param newPassword new password;
     * @return true if change was successful;
     */
    public synchronized boolean changePassword(String username, String newPassword) {
        ArrayList<String> lines = new ArrayList<>();
        boolean changed = false;

        try {
            // Initialize variables
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients.txt");
            Scanner reader = new Scanner(file);

            // Read and edit 'clients.txt'
            while (reader.hasNextLine()) {
                String user = reader.nextLine();
                String[] info = user.split(" / ");

                if (info[0].equals(username)) {
                    user = info[0] + " / " + newPassword + " / " + info[2];
                    changed = true;
                }

                lines.add(user);
            }

            // Close reader
            reader.close();

            // Re-write 'clients.txt'
            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/clients.txt");
            for (String s: lines) fileWriter.write(s + "\n");

            // Close writer
            fileWriter.close();

        }
        catch (FileNotFoundException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        return changed;
    }

    /**
     * > Save user's current directory;
     * @param username username of client;
     * @param currentDir directory;
     * @return true if it was successful;
     */
    public synchronized boolean saveCurrentDir(String username,String currentDir){
        ArrayList<String> lines = new ArrayList<>();
        boolean changed = false;

        try {

            // Initialize variables
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients.txt");
            Scanner reader = new Scanner(file);

            // Read and edit 'clients.txt'
            while (reader.hasNextLine()) {
                String user = reader.nextLine();
                String[] info = user.split(" / ");

                if (info[0].equals(username)) {
                    user = info[0] + " / " + info[1] + " / " + currentDir;
                    changed = true;
                }

                lines.add(user);
            }

            // Close reader
            reader.close();

            // Re-write 'clients.txt'
            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/clients.txt");
            for (String s: lines) fileWriter.write(s + "\n");

            // Close writer
            fileWriter.close();

        }
        catch (FileNotFoundException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        return changed;
    }

    /**
     * > Get server configuration;
     * @return arraylist will all information;
     */
    public synchronized ArrayList<String> getconfig(){
        ArrayList<String> config = new ArrayList<>();

        try {

            // Initialize variables
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/config.txt");
            Scanner reader = new Scanner(file);

            // Go through 'config.txt' file
            while (reader.hasNextLine()) {
                String line = reader.nextLine();

                if(!line.startsWith("// ")){
                    String[] values = line.split(": ");

                    if(values.length==2) config.add(values[1]);
                }
            }

            // Close reader
            reader.close();
        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return config;
    }

    /**
     * > See if there is already a clients.txt;
     * @return bool;
     */
    public synchronized boolean isUpdateClientstxt() { return !updateClientstxt; }

    /**
     * > Update value if 'clients.txt' is inserted or removed from the pipe
     */
    public synchronized void setUpdateClientstxt( boolean updateClientstxt) { this.updateClientstxt = updateClientstxt; }

    /**
     * > Adds a new client to 'client.txt' and creates a new directory for them;
     * > Also makes a backup of the new directory in the secondary server;
     * @param BASE_DIR directory where jar in being run;
     * @param info command received split in the spaces;
     * @param fq file queue used for backup;
     * @return success of failure of the register action;
     */
    public String registerClient(String BASE_DIR, String[] info, BlockingQueue<String> fq){
        ArrayList<String> lines = new ArrayList<>();

        // Check number of arguments
        if (info.length > 7) return "Too many arguments";
        else if (info.length < 7) return "Not enough arguments";

        try {
            Scanner fileReader = new Scanner(new File(BASE_DIR + "/home/clients.txt"));

            // Store 'clients.txt' lines
            while (fileReader.hasNextLine()) {
                String line = fileReader.nextLine();
                lines.add(line);
                String[] splitLine = line.split(" / ");

                if (Objects.equals(splitLine[0], info[1])) return "Client already exists";
            }

            // Close reader
            fileReader.close();

            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/clients.txt");

            // Re-write 'clients.txt'
            for (String s: lines) fileWriter.write(s + "\n");

            // Add new client
            fileWriter.write(String.format("%s / %s / %s/home / %s / %s / %s / %s\n", info[1], info[2], info[1], info[3], info[4], info[5], info[6]));

            // Backup 'clients.txt'
            String aux;
            if (this.isUpdateClientstxt()) {
                aux = "/home/clients.txt";
                System.out.println("DEBUG: placing - " + aux + " in queue");
                fq.put("File");
                fq.put(aux);

                this.setUpdateClientstxt(true);
            }

            // Close writer
            fileWriter.close();

            // Create new directory for client
            if (!new File(BASE_DIR + "/home/" + info[1] + "/home").mkdirs())
                System.out.println("DEBUG: Error creating client folder");

            // Backup new directory
            aux = "/home/" + info[1] + "/home";
            System.out.println("DEBUG: placing - " + aux +" in queue");
            fq.put("Folder");
            fq.put(aux);
        }
        catch (Exception e){
            e.printStackTrace();
        }

        return "New client registered";
    }

    /**
     * > Recursively builds a file tree on StringBuilder 'string';
     * @param folder current folder in recursion;
     * @param indent current indentation in recursion;
     * @param string string where file tree is being written;
     */
    private static void fileTree(File folder, int indent, StringBuilder string){
        File[] files = Objects.requireNonNull(folder.listFiles());

        for (File file : files) {
            if (file.isDirectory()) {

                // Append current folder with correct indentation
                string.append(String.join("", Collections.nCopies(indent, "|  ")));
                string.append("+--");
                string.append(file.getName()).append("/\n");

                // Recursive call
                fileTree(file, indent + 1, string);
            }
            else {

                // Append current file with correct indentation
                string.append(String.join("", Collections.nCopies(indent, "|  ")));
                string.append("+--");
                string.append(file.getName()).append("\n");
            }
        }
    }

    /**
     * > Initializes recursive fileTree function with the received client's folder;
     * @param BASE_DIR directory where jar is being run;
     * @param info command received split in the spaces;
     * @return file tree for specific client 'info[1]'
     */
    public synchronized String clientTree(String BASE_DIR, String[] info){
        // Check number of arguments
        if (info.length > 2) return "Too many arguments";
        else if (info.length < 2) return "Not enough arguments";

        StringBuilder string = new StringBuilder(info[1] + "/\n");

        // Call recursive function
        try {
            fileTree(new File(BASE_DIR + "/home/" + info[1] + "/"), 0, string);
        }
        catch (Exception e){
            return "Client " + info[1] + " doesn't exist";
        }

        return string.toString();
    }

    /**
     * > Edit 'config.txt' to change Heartbeat configuration values;
     * @param BASE_DIR directory where jar is being run;
     * @param info command received split by the spaces;
     * @return success or failure of the configuration action;
     */
    public synchronized String configServer(String BASE_DIR, String[] info){

        // Check number of arguments
        if (info.length > 3) return "Too many arguments";
        else if (info.length < 3) return "Not enough arguments";

        try {
            // Initialize variables
            ArrayList<String> lines = new ArrayList<>();
            Scanner fileReader = new Scanner(new File(BASE_DIR + "/home/config.txt"));

            // Read and edit 'config.txt'
            while (fileReader.hasNextLine()){
                String line = fileReader.nextLine();

                String[] splitServerLine = line.split(": ");
                if (Objects.equals(splitServerLine[0], "DELAY")) lines.add("DELAY: " + info[1]);
                else if (Objects.equals(splitServerLine[0], "MAX FAILED")) lines.add("MAX FAILED: " + info[2]);
                else lines.add(line);
            }

            // Close reader
            fileReader.close();

            // Re-write 'config.txt'
            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/config.txt");
            for (String s: lines) fileWriter.write(s + "\n");

            // Close writer
            fileWriter.close();
        }
        catch (FileNotFoundException e){
            return "Configuration files not found";
        }
        catch (Exception e) {
            return "Error while writing to file";
        }

        return "Configuration file successfully edited";
    }

    /**
     * > Recursively get space occupied by a folder;
     * @param folder folder to get size;
     * @return size of the folder;
     * @throws NullPointerException when folder doesn't exist;
     */
    private static long getFolderSize(File folder) throws NullPointerException{
        long length = 0;

        // Get list of files in folder
        File[] files = folder.listFiles();

        assert files != null;
        for (File file : files)
            // Add file size to 'length'
            if (file.isFile()) length += file.length();

            // Recursive call
            else length += getFolderSize(file);

        return length;
    }

    /**
     * > Initializes getFolderSize function with either the received client's folder or the absolute server path;
     * @param BASE_DIR directory where jar is being run;
     * @param info command received split by the spaces;
     * @return storage occupied by the received client or by the server
     */
    public synchronized String clientStorage(String BASE_DIR, String[] info){

        // Check number of arguments
        if (info.length > 2) return "Too many arguments";
        else if (info.length < 1) return "Not enough arguments";

        // Call recursive function
        if (info.length == 1) return "Total space occupied is: " + getFolderSize(new File(BASE_DIR + "/home/")) + " bytes";
        else
            try {
                return "Client " + info[1] + " is occupying " + getFolderSize(new File(BASE_DIR + "/home/" + info[1])) + " bytes";
            }
            catch (NullPointerException e){
                return "Client " + info[1] + " doesn't exist";
            }
    }

    public static boolean fileExists(String filePath, String address, int port, int ourPort, HeartBeat HB){
        int fExists = 0;
        try {
            DatagramSocket socket = new DatagramSocket(ourPort);

            // Initialize variables
            boolean checkPath;
            byte[] filePathBytes = filePath.getBytes();
            byte[] fileP = new byte[filePathBytes.length + 1];
            fileP[0] = (byte) (1);

            System.arraycopy(filePathBytes, 0, fileP, 1, filePathBytes.length);
            DatagramPacket filePathPacket = new DatagramPacket(fileP, fileP.length, InetAddress.getByName(address), port);

            // Send packet
            socket.send(filePathPacket);
            System.out.printf("DEBUG: Sent: path %s\n", new String(fileP, 1, fileP.length - 1));

            // Check if the path was received correctly
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
            do {
                existsPacket = new DatagramPacket(data, data.length);

                while (true) {
                    try {
                        socket.setSoTimeout(HB.delay * 2);
                        socket.receive(existsPacket);
                        break;
                    }
                    catch (SocketTimeoutException e) {
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

                if (checkExist[0] == (byte) (1) || checkExist[0] == (byte) (0)) dataFlag = true;

            } while (!dataFlag);

            // Close socket
            socket.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        return fExists == 1;
    }

    /**
     * > Recursively builds a file tree on StringBuilder 'string' with integrity checks (check if the file/folder
     *   has or not a backup on the secondary server);
     * @param isValid indicates if the file/folder's parent had a backup or not (in order to relieve some of the
     *                pressure on the UDP connection;
     * @param folder current folder in recursion;
     * @param indent current indentation in recursion;
     * @param string string where file tree is being written;
     * @param hb server heartbeat object
     * @param ipAddress server ip address
     * @param otherPort server port
     * @param port secondary server port
     */
    private static void integrityTree(boolean isValid, File folder, int indent, StringBuilder string, HeartBeat hb, String ipAddress, int otherPort, int port){
        File[] files = Objects.requireNonNull(folder.listFiles());

        for (File file : files) {
            if (file.isDirectory()) {
                // Append current folder with correct indentation
                string.append(String.join("", Collections.nCopies(indent, "|  ")));
                string.append("+--");
                string.append(file.getName()).append("/");

                // Check if it has a backup
                if (!isValid) {
                    string.append("  No\n");

                    // Recursive call
                    integrityTree(false, file, indent + 1, string, hb, ipAddress, otherPort, port);
                }
                else
                    if (fileExists(file.getPath().substring(System.getProperty("user.dir").length()), ipAddress, otherPort, port, hb)) {
                        string.append("  Yes\n");

                        // Recursive call
                        integrityTree(true, file, indent + 1, string, hb, ipAddress, otherPort, port);
                    }
                    else{
                        string.append("  No\n");

                        // Recursive call
                        integrityTree(false, file, indent + 1, string, hb, ipAddress, otherPort, port);
                    }
            }
            else {

                // Append current file with correct indentation
                string.append(String.join("", Collections.nCopies(indent, "|  ")));
                string.append("+--").append(file.getName());

                // Check if it has a backup
                if (!isValid) string.append("  No\n");
                else {
                    if (fileExists(file.getPath().substring(System.getProperty("user.dir").length()), ipAddress, otherPort, port, hb))
                        string.append("  Yes\n");
                    else
                        string.append("  No\n");
                }
            }
        }
    }

    /**
     * Initializes integrityTree function with the absolute server path;
     * @param BASE_DIR directory where jar is being run;
     * @param info command received split by the spaces;
     * @param hb server heartbeat object
     * @param ipAddress server ip address
     * @param otherPort server port
     * @param port secondary server port
     * @return file tree with respective integrity
     */
    public synchronized String checkIntegrity(String BASE_DIR, String[] info, HeartBeat hb, String ipAddress, int otherPort, int port){

        // Check  number of arguments
        if (info.length > 1) return "Too many arguments";
        else if (info.length < 1) return "Not enough arguments";

        StringBuilder string = new StringBuilder("home/  Yes\n");

        // Call recursive function
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
        }
        catch(IOException e){
            System.out.println("DEBUG: Connection: " + e.getMessage());
        }
    }

    /**
     * > Runner will be stuck at login until it is confirmed, then enters loop where all commands are handled;
     * > Exit or reconnect will break loop;
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
     * > Retrieves user's username and password, doesn't leave until it succeeds or is aborted;
     */
    public void login(){

        try{
            // Initialize variables
            ArrayList<String> contents = new ArrayList<>();
            boolean foundUsername = false;
            boolean passwordValid = false;

            // Get username
            while(!foundUsername) {
                out.writeUTF("server - insert username: ");

                contents = fa.getUserInfo(in.readUTF());
                if(contents.size()==0) out.writeUTF("server - Username not found\n");
                else {
                    Username = contents.get(0);
                    foundUsername = true;
                }
            }

            // Get password
            while(!passwordValid) {
                out.writeUTF("server - insert password: ");
                if (in.readUTF().equals(contents.get(1))) passwordValid=true;
                else out.writeUTF("server - wrong password\n");
            }

            // Get last user directory
            currentDir = contents.get(2);
            String BASE_DIR = System.getProperty("user.dir");
            File directory = new File(BASE_DIR + "/home/" + currentDir);

            // Creates user directory if it does not exist
            if (!directory.exists()){
                System.out.println("DEBUG: " + BASE_DIR + "/home/" + currentDir);

                if (directory.mkdirs()) {
                    System.out.println("DEBUG: Directory has been created successfully");

                    String aux = "/home/"  + currentDir;
                    System.out.println("DEBUG: placing - " + aux +" in queue");
                    fq.put("Folder");
                    fq.put(aux);
                }
                else System.out.println("DEBUG: Directory cannot be created");
            }

        }
        catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * > New password handler;
     * @return true if everything went right;
     */
    public boolean newPasswordRequest() {
        try {
            // Ask for new password
            out.writeUTF("server - new password: ");
            String newpass = in.readUTF();

            // Change password
            if(fa.changePassword(Username, newpass)) {
                if (fa.isUpdateClientstxt()) {
                    fq.put("File");
                    fq.put("/home/clients.txt");
                    fa.setUpdateClientstxt(true);
                }
                return true;
            }

        }
        catch(IOException e) {
            System.out.println("IO:" + e);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        return false;
    }

    /**
     * > Send file to client;
     * @param path path where file is;
     */
    public void sendFile(String path, ServerSocket s){
        try {
            Socket cs = s.accept(); // Waiting for client
            System.out.println("DEBUG: connected file transfer socket: " + s);
            DataOutputStream out = new DataOutputStream(cs.getOutputStream());

            // Initialize variables
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

            // Close sockets and stream
            fileInputStream.close();
            cs.close();
            s.close();
        }
        catch (Exception e) {
            System.out.println("DEBUG: client disconnected, download failed");
        }
    }

    /**
     * > Receives file from client;
     * @param fileName name of the file;
     */
    private void receiveFile(String fileName, int port){
        try {
            // Create socket
            Socket s = new Socket(clientSocket.getInetAddress(), port);
            System.out.println("DEBUG: connected file transfer socket: " + s);

            // Initialize variables
            DataInputStream in = new DataInputStream(s.getInputStream());
            int bytes;
            FileOutputStream fileOutputStream = new FileOutputStream(fileName);

            // Read file size
            long size = in.readLong();
            byte[] buffer = new byte[4 * 1024];
            while (size > 0 && (bytes = in.read(buffer, 0, (int) Math.min(buffer.length, size))) != -1) {
                fileOutputStream.write(buffer, 0, bytes);
                size -= bytes;      // Read up to file size
            }

            // Close stream and socket
            fileOutputStream.close();
            s.close();
        }
        catch (IOException e) {
            System.out.println("DEBUG: client disconnected, upload failed");
        }
    }

    /**
     * > Handles all commands received from client;
     * @param command command to be handled;
     * @return text to be displayed in client console or a command to be executed on client side;
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

            else if(command.equals("server")) return "server /" + currentDir + " > ";

            else if(command.equals("rp")) {
                if (newPasswordRequest()) return "/reconnect";
                else out.writeUTF("server - an error as ocorrued\nserver /" + currentDir + " > ");
            }

            else if(command.equals("exit")) return "/exit";

            else if(command.startsWith("cd")){

                // Empty cd command
                if(command.equals("cd")){
                    currentDir = Username + "/home";
                    fa.saveCurrentDir(Username,currentDir);
                    if (fa.isUpdateClientstxt()) {
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
                            if (fa.isUpdateClientstxt()) {
                                fq.put("File");
                                fq.put("/home/clients.txt");
                                fa.setUpdateClientstxt(true);
                            }
                            return "server /" + currentDir + " >";
                        }
                        currentDir = currentDir.substring(0,currentDir.lastIndexOf("/"));
                        fa.saveCurrentDir(Username,currentDir);
                        if (fa.isUpdateClientstxt()) {
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
                            if (fa.isUpdateClientstxt()) {
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
                        if (fa.isUpdateClientstxt()) {
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
                output.append("server /").append(currentDir).append(" > ");

                return output.toString();
            }

            else if(command.startsWith("download ")){
                String[] info = command.split(" ");
                String BASE_DIR = System.getProperty("user.dir");
                File directory = new File(BASE_DIR + "/home/" + currentDir + "/" + info[1]);

                if (!directory.exists())
                    return "server: file does not exist" +
                            "\nserver /" + currentDir + " > ";

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
                    }
                    else System.out.println("DEBUG: an error ocurred while creating folder");
                }
                else receiveFile(BASE_DIR +"/home/"+ Username + info[1], Integer.parseInt(info[2]));

                String aux = "/home/"+ Username + info[1];
                System.out.println("DEBUG: placing - " + aux +" in queue");
                fq.put("File");
                fq.put(aux);

                return "";
            }

        }
        catch (Exception e) {
            e.printStackTrace();
        }

        return "server: invalid command\nserver /" + currentDir + " > ";
    }
}

class ConnectionUDP extends Thread {
    private final int serverHierarchy;
    private final String address;
    private final int port;
    private final int ourPort;
    private final FileAccess fa;
    private BlockingQueue<String> fq;
    private HeartBeat HB;

    public ConnectionUDP(int serverHierarchy, String address, int port,int otherport,BlockingQueue<String> filequeue,FileAccess f){
        this.serverHierarchy = serverHierarchy;
        this.address = address;
        this.port = port;
        this.ourPort = otherport;
        this.fq = filequeue;
        this.fa = f;
    }
    public ConnectionUDP(int serverHierarchy, String address, int port,int otherport, HeartBeat hb,FileAccess f){
        this.serverHierarchy = serverHierarchy;
        this.address = address;
        this.port = port;
        this.ourPort = otherport;
        this.HB = hb;
        this.fa = f;
    }

    /**
     * > Runner identifies the hierarchy of the server, so it can be the sender (primary) or the
     *   receiver (secondary) of backup files
     */
    public void run() {
        if (serverHierarchy == 1) {
            // Send
            while (true) {
                try {
                    String type = fq.take();
                    String path = fq.take();
                    System.out.println("DEBUG: saving file: " + path + " on second server");
                    sendFileUDP(type, path);
                    if (path.equals("/home/clients.txt")) fa.setUpdateClientstxt(false);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        else {
            // Receive
            boolean run = true;
            while (run) run = receiveFileUDP();
        }
    }

    /**
     * > Reads a file and converts it to a byte array
     * @param file file to be read
     * @return byte array of the file
     */
    public static byte[] readFileToByteArray(File file) {
        byte[] b = new byte[(int) file.length()];
        try {
            FileInputStream fis = new FileInputStream(file);
            if (fis.read(b) == -1) System.out.println("DEBUG: Error while reading file");
            fis.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return b;
    }

    /**
     * > Sends a file or directory over UDP;
     * @param fileType type of the file (file or folder);
     * @param filePath path of the file;
     */
    public void sendFileUDP(String fileType, String filePath) {
        System.out.println("DEBUG: Backup Started");
        String type_path = fileType + '_' + filePath;

        try {
            DatagramSocket socket = new DatagramSocket(ourPort);

            // Initialize variables
            byte[] fileTypePathBytes = type_path.getBytes();
            byte[] fileTP = new byte[type_path.getBytes().length + 1];
            fileTP[0] = (byte) (1);

            System.arraycopy(fileTypePathBytes, 0, fileTP, 1, fileTypePathBytes.length);
            DatagramPacket fileTypePathPacket = new DatagramPacket(fileTP, fileTP.length, InetAddress.getByName(address), port);

            // Send packet
            socket.send(fileTypePathPacket);
            System.out.printf("DEBUG: Sent: path %s\n", new String(fileTP, 1, fileTP.length - 1));

            // Check if the path was received correctly
            while (true) {
                byte[] check = new byte[1];
                boolean checkTypePath = false;
                DatagramPacket checkPacket = new DatagramPacket(check, check.length);

                try {
                    // Set timeout
                    socket.setSoTimeout(500);
                    socket.receive(checkPacket);
                    checkTypePath = true;
                } catch (SocketTimeoutException e) {
                    System.out.println("DEBUG: Socket timed out waiting for acknowledgement");
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

                // Initialize variables
                int sequenceNumber = 0;    // For order
                boolean eofFlag;           // To see if EOF was reached
                int ackSequence = 0;       // To see if the datagram was received correctly

                for (int i = 0; i < fileBytes.length; i = i + 1021) {

                    // Send part of the file
                    sequenceNumber += 1;

                    byte[] data = new byte[1024]; // First byte: integrity; Second Byte: order; Third Byte: EOF
                    data[0] = (byte) (sequenceNumber >> 8);
                    data[1] = (byte) (sequenceNumber);

                    if ((i + 1021) >= fileBytes.length) {
                        eofFlag = true;
                        data[2] = (byte) (1);
                    }
                    else {
                        eofFlag = false;
                        data[2] = (byte) (0);
                    }

                    if (!eofFlag) System.arraycopy(fileBytes, i, data, 3, 1021);
                    else System.arraycopy(fileBytes, i, data, 3, fileBytes.length - i);

                    // Send packet
                    DatagramPacket sendPacket = new DatagramPacket(data, data.length, InetAddress.getByName(address), port);
                    socket.send(sendPacket);
                    System.out.println("DEBUG: Sent: sequence number = " + sequenceNumber);

                    // Check if the part of the file was received correctly
                    while (true) {
                        byte[] check = new byte[2];
                        boolean checkReceived = false;
                        DatagramPacket checkPacket = new DatagramPacket(check, check.length);

                        try {
                            socket.setSoTimeout(500);
                            socket.receive(checkPacket);
                            ackSequence = ((check[0] & 0xff) << 8) + (check[1] & 0xff);
                            checkReceived = true;
                        } catch (SocketTimeoutException e) {
                            System.out.println("DEBUG: Socket timed out waiting for acknowledgement");
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

            // Close socket
            socket.close();
        } catch (SocketException e) {
            System.out.println("DEBUG: Socket UDP Sender: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }

    /**
     * > Receives a file or directory over UDP;
     * @return meant to be true until heartbeat is dead and server needs to become primary;
     */
    public boolean receiveFileUDP() {
        System.out.println("DEBUG: Backup Started");

        try {
            DatagramSocket socket = new DatagramSocket(ourPort);

            // Receive Path
            byte[] data;
            DatagramPacket fileTypePathPacket;
            boolean dataFlag = false;
            do {
                byte[] fileTypePathBytes = new byte[1024];
                fileTypePathPacket = new DatagramPacket(fileTypePathBytes, fileTypePathBytes.length);

                while (true) {
                    try {
                        socket.setSoTimeout(HB.delay * 2);
                        socket.receive(fileTypePathPacket);
                        break;
                    }
                    catch (SocketTimeoutException e) {
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

                if (checkTypePath[0] == (byte) (1)) dataFlag = true;

            } while (!dataFlag);


            String fileTypePath = new String(data, 1, fileTypePathPacket.getLength()-1);
            System.out.println("DEBUG: Type and Path received: " + fileTypePath);
            String fileType = fileTypePath.substring(0, fileTypePath.indexOf("_"));
            String filePath = fileTypePath.substring(fileTypePath.indexOf("_")+1);

            if (fileType.equals("File")) {
                // Receive File
                System.out.println("DEBUG: Opening " + System.getProperty("user.dir") + filePath);
                File f = new File(System.getProperty("user.dir") + filePath);
                FileOutputStream fos = new FileOutputStream(f);

                int sequenceNumber;  // For order
                boolean eofFlag;     // To see if EOF was reached
                int foundLast = 0;   // To see the last sequence found

                while (true) {
                    byte[] message = new byte[1024];
                    byte[] fileByteArray = new byte[1021];

                    DatagramPacket receivedPacket = new DatagramPacket(message, message.length);

                    while (true) {
                        try {
                            socket.setSoTimeout(HB.delay*2);
                            socket.receive(receivedPacket);
                            break;
                        }
                        catch (SocketTimeoutException e) {
                            if (!HB.isAlive()) {
                                System.out.println("DEBUG: Closing Backup, HeartBeat has terminated");
                                socket.close();
                                return false;
                            }
                        }
                    }

                    message = receivedPacket.getData();

                    // Make sure that control and sequence bytes are ok
                    sequenceNumber = ((message[0] & 0xff) << 8) + (message[1] & 0xff);
                    eofFlag = (message[2] & 0xff) == 1;

                    if (sequenceNumber == (foundLast + 1)) {
                        foundLast = sequenceNumber;

                        System.arraycopy(message, 3, fileByteArray, 0, 1021);

                        fos.write(fileByteArray);
                        System.out.println("DEBUG: Received acknowledgement: sequence number: " + foundLast);
                    }
                    else
                        System.out.println("DEBUG: Expected sequence number: " + (foundLast + 1) + " but received " + sequenceNumber + ", discarding this packet");

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
    private final String address;
    private final int port;
    private final int ourPort;
    private final HeartBeat HB;

    public IntegrityUDP(String address, int port, int otherport, HeartBeat hb) {
        this.address = address;
        this.port = port;
        this.ourPort = otherport;
        this.HB = hb;
    }

    /**
     * > Runner keeps the function to check integrity running;
     */
    public void run() {
        // Receive
        boolean run = true;

        System.out.println("DEBUG: Opening Integrity Check");
        while (run) run = checkIntegrity();
        System.out.println("DEBUG: Closing Integrity Check");
    }

    /**
     * > Receives a path over UDP to check if it exists in secondary server;
     * @return meant to be true until heartbeat is dead and server needs to become primary;
     */
    public boolean checkIntegrity() {
        try {
            // Create socket
            DatagramSocket socket = new DatagramSocket(ourPort);

            // Initialize variables
            byte[] data;
            DatagramPacket filePathPacket;
            boolean dataFlag = false;

            do {
                byte[] filePathBytes = new byte[1024];
                filePathPacket = new DatagramPacket(filePathBytes, filePathBytes.length);

                while (true) {
                    try {
                        // Receive path
                        socket.setSoTimeout(HB.delay * 2);
                        socket.receive(filePathPacket);
                        break;
                    } catch (SocketTimeoutException e) {
                        // Check if primary server is still alive
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

                if (checkPath[0] == (byte) (1)) dataFlag = true;
            } while (!dataFlag);

            String filePath = new String(data, 1, filePathPacket.getLength() - 1);
            System.out.println("DEBUG: Path received: " + filePath);

            // Check if path exists or not
            File f = new File(System.getProperty("user.dir") + filePath);
            byte[] fileExists = new byte[1];
            if (f.exists()) fileExists[0] = (byte) (1);

            DatagramPacket fileExistsPacket = new DatagramPacket(fileExists, fileExists.length, InetAddress.getByName(address), port);
            socket.send(fileExistsPacket);
            System.out.println("DEBUG: Sent: file exist value = " + fileExists[0]);

            // Check if value was received correctly
            while (true) {
                byte[] check = new byte[1];
                boolean confirmationReceived = false;
                DatagramPacket checkPacket = new DatagramPacket(check, check.length);

                try {
                    // Set socket timeout
                    socket.setSoTimeout(500);

                    // Receive value
                    socket.receive(checkPacket);
                    confirmationReceived = true;
                } catch (SocketTimeoutException e) {
                    System.out.println("DEBUG: Socket timed out waiting for acknowledgement");
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

            // Close socket
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
    private final BlockingQueue<String> fq;
    private final String ipAddress;
    private final int port;
    private final int ourPort;

    public AdminConsole(FileAccess FA, HeartBeat HB, BlockingQueue<String> FQ, String ipAddress, int port, int ourPort) throws RemoteException {
        super();

        this.fa = FA;
        this.hb = HB;
        this.fq = FQ;
        this.ipAddress = ipAddress;
        this.port = port;
        this.ourPort = ourPort;
    }

    public String adminCommandHandler(String command){
        // Split command
        String[] info = command.split(" ");

        // Get absolute path for directory where jar is running
        String BASE_DIR = System.getProperty("user.dir");

        // Get command response
        return switch (info[0]) {
            case "reg"     -> fa.registerClient(BASE_DIR, info, fq);
            case "tree"    -> fa.clientTree(BASE_DIR, info);
            case "config"  -> fa.configServer(BASE_DIR, info);
            case "storage" -> fa.clientStorage(BASE_DIR, info);
            case "integrity" -> fa.checkIntegrity(BASE_DIR, info, hb, ipAddress, port, ourPort);
            case "help" -> """

                    \texit - end RMI connection
                    \treg client password faculty address phone CC - create new client
                    \ttree client - check file tree
                    \tconfig delay max_failed - edit heartbeat configurations
                    \tstorage (client) - check how much space is being used
                    \tintegrity - check what files are backed up in 2nd server
                    """;
            default -> "Invalid command";
        };
    }
}