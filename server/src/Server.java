import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Scanner;

public class Server{

    public static void main(String[] args){
        Scanner sc = new Scanner(System.in);

        String serverAddress;
        int serverPort;

        ArrayList<String> connectionInfo = getConnectionInfo(sc);
        serverAddress = connectionInfo.get(0);
        serverPort = Integer.parseInt(connectionInfo.get(1));

        FileAccess FA = new FileAccess();

        try {
            ServerSocket listenSocket = new ServerSocket();
            SocketAddress sockaddr = new InetSocketAddress(serverAddress, serverPort);
            listenSocket.bind(sockaddr);

            System.out.println("DEBUG: Server started at port " + serverPort + " with socket " + listenSocket);
            while(true) {
                Socket clientSocket = listenSocket.accept(); // BLOQUEANTE
                System.out.println("DEBUG: Client connected, clientsocket = "+clientSocket);
                new Connection(clientSocket, FA);
            }
        } catch(IOException e) {
            System.out.println("Listen: " + e.getMessage());
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
            fa.saveCurrentDir(Username, currentDir);

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

    public String commandHandler(String command) {
        try {
            if(command.equals("help")){
                String commands = "";
                commands += "\n\trp - reset password\n";
                commands += "\texit - leave server\n";
                commands += "\tcd - home directory\n";
                commands += "\tcd .. - previous directory\n";
                commands += "\tcd dir - go to dir directory\n";
                commands += "\tmkdir dir - create dir directory\n";
                return commands + "\nserver /" + currentDir + " > ";
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
                else
                    System.out.println("DEBUG: Directory already exists");
                    currentDir = currentDir + "/" + newFolder;
                    return "server /" + currentDir + " > " ;
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

                // check if info[1] aka file exists
                // check if info[2] aka path localy exists
                    // probably send command to client and get client to check if he has that dir
                // por agora mandar pela mesma socket mas temos que ter duas

                out.writeUTF("/file_download "+info[1]);
                String BASE_DIR = System.getProperty("user.dir");
                String filepath = BASE_DIR + "/home/" + currentDir +"/"+ info[1];
                sendFile(filepath);
                return "poopy\n";
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return "invalid command\nserver /" + currentDir + " > ";
    }
}
