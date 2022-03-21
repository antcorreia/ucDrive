import java.net.*;
import java.io.*;
import java.util.ArrayList;
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

            System.out.println("Server started at port " + serverPort + " with socket " + listenSocket);
            while(true) {
                Socket clientSocket = listenSocket.accept(); // BLOQUEANTE
                System.out.println("Client connected, clientsocket = "+clientSocket);
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
            System.out.println("File not found.");
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
                    user = info[0] + " / " + newPassword;
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
        }catch(IOException e){System.out.println("Connection: " + e.getMessage());}
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
                out.writeUTF("Insert Username: ");
                contents = fa.getUserInfo(in.readUTF());
                if(contents.size()==0)
                    out.writeUTF("Username not found\nserver /" + currentDir + " > ");
                else {
                    Username = contents.get(0);
                    foundUsername = true;
                }

            }
            while(!passwordValid) {
                out.writeUTF("Insert Password: ");
                if (in.readUTF().equals(contents.get(1)))
                    passwordValid=true;
                else
                    out.writeUTF("Wrong Password\n");
            }

            currentDir = contents.get(2);
            String BASE_DIR = System.getProperty("user.dir");
            File directory = new File(BASE_DIR + "/home/" + currentDir);
            if (! directory.exists()){
                System.out.println("DEBUG: " + BASE_DIR + "/home/" + currentDir);
                if (directory.mkdirs()) {
                    System.out.println("DEBUG: Directory has been created successfully");
                }
                else {
                    System.out.println("DEBUG: Directory cannot be created");
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean newPasswordRequest() {
        try {
            out.writeUTF("server > new password: ");
            String newpass = in.readUTF();
            if(fa.changePassword(Username, newpass))
                return true;

        }
        catch(IOException e) {
            System.out.println("IO:" + e);
        }
        return false;
    }

    public String commandHandler(String command) {
        try {
            if(command.equals("rp")){
                if(newPasswordRequest()) {
                    return "/reconnect";
                }
                else{
                    out.writeUTF("server > an error as ocorrued");
                }
            }
            else if(command.equals("exit")){
                return "/exit";
            }

            else if(command.substring(0,2).equals("cd")){
                if(command.equals("cd")){
                    currentDir = Username + "/home";
                    return "server /" + currentDir + " > ";
                }
                else{
                    String nextCommand = command.substring(2,command.length());
                    if(nextCommand.equals(" ..")){
                        if(currentDir.equals(Username + "/home")){
                            return "server /" + currentDir + " >";
                        }
                        currentDir = currentDir.substring(0,currentDir.lastIndexOf("/"));
                        return "server /" + currentDir + " > " ;
                    }
                    if(nextCommand.charAt(0) == ' '){
                        String nextDir = nextCommand.substring(1,nextCommand.length());
                        String BASE_DIR = System.getProperty("user.dir");
                        File directory = new File(BASE_DIR + "/home/" + currentDir + "/" + nextDir);
                        if (directory.exists()) {
                            currentDir = currentDir + "/" + nextDir;
                            return "server /" + currentDir + " > " ;
                        }
                        else
                            return "Folder doesn't exist\nserver /"  + currentDir + " > ";
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return "invalid command\nserver /" + currentDir + " > ";
    }
}
