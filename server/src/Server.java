import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Scanner;

public class Server{

    public static void main(String args[]){

        int serverPort = 6000;
        try (ServerSocket listenSocket = new ServerSocket(serverPort)) {
            System.out.println("Server started at port " + serverPort + " with socket " + listenSocket);
            while(true) {
                Socket clientSocket = listenSocket.accept(); // BLOQUEANTE
                System.out.println("Client connected, clientsocket ="+clientSocket);
                new Connection(clientSocket);
            }
        } catch(IOException e) {
            System.out.println("Listen: " + e.getMessage());
        }
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
                fromClient = in.readUTF();
                response = commandHandler(fromClient);

            }
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
            String BASE_DIR = System.getProperty("user.dir");
            File file = new File(BASE_DIR + "/home/clients/clients.txt");
            Scanner reader = new Scanner(file);
            while (reader.hasNextLine()) {
                String user = reader.nextLine();
                String[] info = user.split(" / ");
                if (info[0].equals(username)) {
                    usernameAndPassord.add(info[0]);
                    usernameAndPassord.add(info[1]);
                    System.out.printf("Username: %s | Password: %s\n", usernameAndPassord.get(0), usernameAndPassord.get(1));
                }
            }
            reader.close();
        } catch (FileNotFoundException e) {
            System.out.println("File not found.");
            e.printStackTrace();
        }

        return usernameAndPassord;
    }

    public boolean changePassword(String username, String newPassword) {
        // ver se a password é valida?
        ArrayList<String> lines = new ArrayList<>();
        boolean changed = false;
        try {
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

        return changed;
    }

    public boolean newPasswordRequest() throws IOException {
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
            if(newPasswordRequest())
                return "/reconnect";
            else{
                out.writeUTF("server > an error as ocorrued");
            }
        }
        return "invalid command\n";
    }
}