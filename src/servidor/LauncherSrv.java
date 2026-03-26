package servidor;

import java.io.IOException;
import java.net.Socket;

public class LauncherSrv {

    private static final String HOST = "localhost";
    private static final int PORT = 12345;

    public static void main(String[] args) {

        System.out.println("🟢 Launcher iniciado. Monitoreando servidor...");

        while (true) {
            try {
                // Intentar conectar para verificar si el servidor está activo
                Socket socket = new Socket(HOST, PORT);
                socket.close();

                System.out.println("✅ Servidor ya está activo.");

            } catch (IOException e) {
                // Si falla → servidor no está activo → lo lanzo
                System.out.println("⚠️ Servidor caído. Iniciando...");

                try {
                    new ProcessBuilder(
                            "java",
                            "-cp",
                            System.getProperty("java.class.path"),
                            "servidor.PrincipalSrv"
                    ).start();

                    System.out.println("🚀 Servidor lanzado automáticamente.");

                    // Esperar para evitar múltiples instancias
                    Thread.sleep(5000);

                } catch (Exception ex) {
                    System.out.println("❌ Error al lanzar servidor: " + ex.getMessage());
                }
            }

            try {
                Thread.sleep(3000); // chequeo cada 3s
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}