import java.rmi.registry.LocateRegistry;
import java.util.*;

public class Admin {

    /**
     * > Function to establish RMI connection to server;
     * > Uses Server's 'adminCommandHandler' to handle the inputted commands;
     */
    public static void main(String[] args){
        try {
            // Establish RMI connection
            AdminInterface rmi = (AdminInterface) LocateRegistry.getRegistry(7001).lookup("admin");

            // Visually initialize admin console
            System.out.print("Admin > ");
            Scanner scanner = new Scanner(System.in);
            String command = scanner.nextLine();

            // Loop to handle commands
            while (!Objects.equals(command, "exit")){
                String message = rmi.adminCommandHandler(command);
                System.out.print(message + "\nAdmin > ");
                command = scanner.nextLine();
            }

        }
        catch (Exception e) {
            System.out.println("Could not establish RMI connection");
        }
    }
}
