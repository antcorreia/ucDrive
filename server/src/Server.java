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

        try {
            ServerSocket listenSocket = new ServerSocket();
            SocketAddress sockaddr = new InetSocketAddress(serverAddress, serverPort);
            listenSocket.bind(sockaddr);

            System.out.println("Server started at port " + serverPort + " with socket " + listenSocket);
            while(true) {
                Socket clientSocket = listenSocket.accept(); // BLOQUEANTE
                System.out.println("Client connected, clientsocket = "+clientSocket);
                new Connection(clientSocket);
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

class Connection extends Thread {
    private DataInputStream in;
    private DataOutputStream out;
    private Socket clientSocket;
    private String Username;

    public Connection (Socket aClientSocket) {
        try{
            clientSocket = aClientSocket;
            in = new DataInputStream(clientSocket.getInputStream());
            out = new DataOutputStream(clientSocket.getOutputStream());
            this.start();
        }catch(IOException e){System.out.println("Connection: " + e.getMessage());}
    }

    public void run(){
        try {
            login();
            String response = "server > ";
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
                contents = getUsernameAndPassword(in.readUTF());
                if(contents.size()==0)
                    out.writeUTF("Username not found\n");
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
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public ArrayList<String> getUsernameAndPassword(String username) {
        ArrayList<String> usernameAndPassord = new ArrayList<>();
        try {
            wait();

            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients/clients.txt");
            Scanner reader = new Scanner(file);
            while (reader.hasNextLine()) {
                String user = reader.nextLine();
                String[] info = user.split(" / ");
                if (info[0].equals(username)) {
                    usernameAndPassord.add(info[0]);
                    usernameAndPassord.add(info[1]);
                    System.out.printf("DEBUG: Username: %s | Password: %s\n", usernameAndPassord.get(0), usernameAndPassord.get(1));
                }
            }
            reader.close();
        } catch (FileNotFoundException e) {
            System.out.println("File not found.");
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return usernameAndPassord;
    }

    public boolean changePassword(String username, String newPassword) {
        // ver se a password é valida?
        ArrayList<String> lines = new ArrayList<>();
        boolean changed = false;
        try {
            wait();

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
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        return changed;
    }

    public boolean newPasswordRequest() {
        try {
            out.writeUTF("server > new password: ");
            String newpass = in.readUTF();
            if(changePassword(Username, newpass))
                return true;

        }
        catch(IOException e) {
            System.out.println("IO:" + e);
        }
        return false;
    }

    public String commandHandler(String command) throws IOException {
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
        return "invalid command\n";
    }
}
