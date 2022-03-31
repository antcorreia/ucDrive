import java.rmi.*;

public interface AdminInterface extends Remote{
    public String adminCommandHandler(String command) throws java.rmi.RemoteException;
}
