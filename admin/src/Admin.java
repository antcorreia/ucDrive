import java.rmi.registry.LocateRegistry;
import java.util.*;

public class Admin {
    public static void main(String[] args){
        try {
            AdminInterface rmi = (AdminInterface) LocateRegistry.getRegistry(7001).lookup("admin");
            System.out.print("Admin > ");

            Scanner scanner = new Scanner(System.in);
            String command = scanner.nextLine();
            while (!Objects.equals(command, "exit")){
                String message = rmi.adminCommandHandler(command);
                System.out.print(message + "\nAdmin > ");
                command = scanner.nextLine();
            }

        } catch (Exception e) {
            System.out.println("Could not establish RMI connection");
        }
    }
}
