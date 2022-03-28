import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Scanner;

public class Server{

    public static void main(String[] args){
        Scanner sc = new Scanner(System.in);

        ArrayList<String> connectionInfo = getConnectionInfo(sc);
        String serverAddress = connectionInfo.get(0);
        int serverPort = Integer.parseInt(connectionInfo.get(1));
        int serverHierarchy = Integer.parseInt(connectionInfo.get(2));
        int hbPort = Integer.parseInt(connectionInfo.get(3));
        FileAccess FA = new FileAccess();

        if(serverHierarchy == 2){
            HeartBeat HB = new HeartBeat(hbPort,false,"1");
            HB.start();
            try {
                HB.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        try {

            ServerSocket listenSocket = new ServerSocket();
            SocketAddress sockaddr = new InetSocketAddress(serverAddress, serverPort);
            listenSocket.bind(sockaddr);

            HeartBeat HB = new HeartBeat(hbPort,true);
            HB.start();

            System.out.println("DEBUG: Server started at port " + serverPort + " with socket " + listenSocket);
            while(true) {
                Socket clientSocket = listenSocket.accept(); // BLOQUEANTE
                System.out.println("DEBUG: Client connected, clientsocket = "+clientSocket);
                new Connection(clientSocket, FA);
            }
        } catch(IOException e) {
            e.printStackTrace();
            System.out.println("Listen: " + e.getMessage());
        }
    }

    public static ArrayList<String> getConnectionInfo(Scanner sc) {
        ArrayList<String> info = new ArrayList<>();

        System.out.print("Insert the Server IP Address: ");
        info.add(sc.nextLine());
        System.out.print("Insert the Server Port: ");
        info.add(sc.nextLine());
        System.out.print("Press 1 if server is primary, 2 if is secundary: ");
        info.add(sc.nextLine());
        System.out.print("HeartBeat Port: ");
        info.add(sc.nextLine());
        if(info.get(2).equals("2")){
            System.out.print("Main IP: ");
            info.add(sc.nextLine());
        }

        return info;
    }
}

class HeartBeat extends Thread{

    private DatagramPacket request, reply;
    private MulticastSocket socket;
    private static byte[] buffer = new byte[1024];
    private boolean primary;
    public static int hb_cont;
    public static int hb_default;

    public HeartBeat(int port,boolean hierarchy){

        try {
            socket = new MulticastSocket(port);
            InetAddress group = InetAddress.getByName("224.3.2.1");
            socket.joinGroup(group);
        } catch (IOException e) {
            e.printStackTrace();
        }
        primary = hierarchy;
        request = new DatagramPacket(buffer, buffer.length);
    }
    public HeartBeat(int port,boolean hierarchy, String message){

        try {
            socket = new MulticastSocket();
            InetAddress group = InetAddress.getByName("224.3.2.1");
            reply = new DatagramPacket(buffer, buffer.length, group, port);
        } catch (IOException e) {
            e.printStackTrace();
        }

        buffer = message.getBytes();
        request = new DatagramPacket(buffer, buffer.length);
        primary = hierarchy;
        hb_cont = 5; // read from config file
        hb_default = hb_cont;

    }

    public void ackping(){

        while (true) {
            try {
                socket.receive(request);
                System.out.println("recebi");
                reply = new DatagramPacket(request.getData(), request.getLength(), request.getAddress(), request.getPort());
                socket.send(reply);
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    public void run(){
        if(primary) {
            ackping();
        }
        else{
            PingRecv pingrecv = new PingRecv(request,socket);
            pingrecv.start();
            PingSend pingsend = new PingSend(reply,socket);
            pingsend.start();

            try {
                pingrecv.join();
                pingsend.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }

    }

    public static class PingRecv extends Thread{

        private DatagramPacket request;
        private MulticastSocket socket;
        private int delay;

        public PingRecv( DatagramPacket r, MulticastSocket s){
            request = r;
            socket = s;
            delay = 500; // READ FROM CONFIG
        }

        public void run(){

            while(true){
                try {
                    socket.setSoTimeout(delay*2);
                    hb_cont = hb_default;
                    socket.receive(request);
                    System.out.println("recebi");
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

    public static class PingSend extends Thread{

        private DatagramPacket reply;
        private MulticastSocket socket;
        private int delay;

        public PingSend(DatagramPacket r,MulticastSocket s){
            delay = 500; // read from config file
            socket = s;
            reply = r;
        }

        public void run(){

            while(true){
                try {
                    socket.send(reply);
                    System.out.println("mandei");
                    hb_cont--;
                    Thread.sleep(delay);
                    if(hb_cont<0){
                        break;
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }

        }

    }

}

class FileAccess {

    Semaphore sem = new Semaphore(1);

    public ArrayList<String> getUserInfo(String username) {

        ArrayList<String> userinfo = new ArrayList<>();
        try {
            sem.doWait();
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients/clients.txt");
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
        sem.doSignal();
        return userinfo;
    }

    public  boolean changePassword(String username, String newPassword) {
        // ver se a password é valida?
        ArrayList<String> lines = new ArrayList<>();
        boolean changed = false;
        try {
            sem.doWait();
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients/clients.txt");
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

            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/clients/clients.txt");
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
        sem.doSignal();
        return changed;
    }

    public boolean saveCurrentDir(String username,String currentDir){
        ArrayList<String> lines = new ArrayList<>();
        boolean changed = false;
        try {
            sem.doWait();
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients/clients.txt");
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

            FileWriter fileWriter = new FileWriter(BASE_DIR + "/home/clients/clients.txt");
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
        sem.doSignal();
        return changed;
    }

}

class Semaphore {

    int val;

    public Semaphore(int val) {
        this.val = val;
    }

    public synchronized void doSignal() {
        val++;
        notify();
    }

    public synchronized void doWait() {
        while (val <= 0) {
            try {
                wait();
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        val--;
    }
}

class Connection extends Thread {
    private DataInputStream in;
    private DataOutputStream out;
    private Socket clientSocket;
    private String Username;
    private String currentDir;
    private FileAccess fa;

    public Connection (Socket aClientSocket, FileAccess FA) {
        try{
            clientSocket = aClientSocket;
            in = new DataInputStream(clientSocket.getInputStream());
            out = new DataOutputStream(clientSocket.getOutputStream());
            fa = FA;
            this.start();
        }catch(IOException e){System.out.println("DEBUG: Connection: " + e.getMessage());}
    }

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
                    return "server /" + currentDir + " > ";
                }
                else{
                    String nextCommand = command.substring(2);
                    if(nextCommand.equals(" ..")){
                        if(currentDir.equals(Username + "/home")){
                            return "server /" + currentDir + " >";
                        }
                        currentDir = currentDir.substring(0,currentDir.lastIndexOf("/"));
                        return "server /" + currentDir + " > " ;
                    }
                    if(nextCommand.charAt(0) == ' '){
                        String nextDir = nextCommand.substring(1);
                        String BASE_DIR = System.getProperty("user.dir");
                        File directory = new File(BASE_DIR + "/home/" + currentDir + "/" + nextDir);
                        if (directory.exists()) {
                            currentDir = currentDir + "/" + nextDir;
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

            else if(command.startsWith("save ")){
                String[] info = command.split(" ");
                String BASE_DIR = System.getProperty("user.dir");
                File directory = new File(BASE_DIR + "/home/" + currentDir + "/" + info[1]);
                if (!directory.exists()){
                    return "server: file does not exist" +
                            "\nserver /" + currentDir + " > ";
                }
                out.writeUTF("/file_download "+info[2]+info[1]);

                String filepath = BASE_DIR + "/home/" + currentDir +"/"+ info[1];
                sendFile(filepath);
                return "server: download complete\nserver /" + currentDir + " > ";
            }

            else if(command.startsWith("upload ")) {
                String[] info = command.split(" ");
                String BASE_DIR = System.getProperty("user.dir");
                String dir = info[1].substring(0, info[1].lastIndexOf("/")); // get dir until file
                File directory = new File(BASE_DIR +"/home/"+ Username + "/" + dir);
                if (!directory.exists()) {
                    if (directory.mkdirs()) {
                        receiveFile(BASE_DIR + BASE_DIR +"/home/"+ Username + "/" + info[1]);
                    } else {
                        System.out.println("DEBUG: an error ocurred while creating folder");
                    }
                } else {
                    receiveFile(BASE_DIR +"/home/"+ Username + "/" + info[1]);
                }
                return "";
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return "server: invalid command\nserver /" + currentDir + " > ";
    }
}

