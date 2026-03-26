package servidor;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor TCP con tres políticas:
 *
 *  A) DETENER manual → NO reinicia por caída.
 *     Activa el vigilante para reaccionar si un cliente llama.
 *
 *  B) Caída inesperada → reinicio automático con backoff (hasta MAX_REINTENTOS).
 *     Si agota los reintentos, activa el vigilante.
 *
 *  C) Vigilante → escucha en el puerto cuando el servidor está inactivo.
 *     Al detectar un cliente, reinicia el servidor completo.
 *     El vigilante también arranca al abrir la app (antes de pulsar INICIAR),
 *     de modo que si hay clientes esperando se activa el servidor automáticamente.
 */
public class PrincipalSrv extends JFrame {

    // ── Configuración ────────────────────────────────────────────────────────
    private static final int PORT               = 12345;
    private static final int MAX_REINTENTOS     = 5;
    private static final int DELAY_REINICIO_SEG = 5;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Registro de clientes ──────────────────────────────────────────────────
    private static class InfoCliente {
        final String nombre;
        int     sesiones       = 0;
        int     mensajes       = 0;
        String  ultimaConexion = "—";
        boolean conectado      = false;
        InfoCliente(String n) { this.nombre = n; }
    }
    private final Map<String, InfoCliente> registro = new ConcurrentHashMap<>();

    // ── Estado ───────────────────────────────────────────────────────────────
    private ServerSocket    serverSocket;
    private ExecutorService clientPool;

    private final AtomicBoolean corriendo        = new AtomicBoolean(false);
    private final AtomicBoolean detenerManual    = new AtomicBoolean(false);
    private final AtomicBoolean vigilanteActivo  = new AtomicBoolean(false);
    private final AtomicInteger intentosReinicio = new AtomicInteger(0);

    // ── GUI ───────────────────────────────────────────────────────────────────
    private JButton           btnIniciar, btnDetener;
    private JTextArea         logTxt;
    private JLabel            lblEstado;
    private DefaultTableModel modeloTabla;

    // ─────────────────────────────────────────────────────────────────────────
    public PrincipalSrv() {
        initComponents();
        // Arrancar el vigilante al abrir la app:
        // si un cliente ya está intentando conectarse antes de pulsar INICIAR,
        // el servidor se activará automáticamente.
        iniciarVigilante("Vigilante activo desde inicio de la app.");
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    private void initComponents() {
        setTitle("Servidor TCP – Política de Reinicio");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(600, 530);
        setLocationRelativeTo(null);
        getContentPane().setLayout(null);

        JLabel titulo = new JLabel("SERVIDOR TCP", SwingConstants.CENTER);
        titulo.setFont(new Font("Tahoma", Font.BOLD, 16));
        titulo.setForeground(new Color(180, 0, 0));
        titulo.setBounds(10, 8, 565, 22);
        add(titulo);

        btnIniciar = new JButton("▶  INICIAR");
        btnIniciar.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        btnIniciar.setBounds(20, 40, 130, 34);
        btnIniciar.addActionListener(e -> {
            // Si el vigilante está corriendo, detenerlo antes de arrancar el servidor
            vigilanteActivo.set(false);
            iniciarServidor(false);
        });
        add(btnIniciar);

        btnDetener = new JButton("■  DETENER");
        btnDetener.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        btnDetener.setBounds(165, 40, 130, 34);
        btnDetener.setEnabled(false);
        btnDetener.addActionListener(e -> detenerManualmente());
        add(btnDetener);

        lblEstado = new JLabel("Estado: DETENIDO – vigilante activo");
        lblEstado.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblEstado.setForeground(Color.DARK_GRAY);
        lblEstado.setBounds(20, 82, 550, 18);
        add(lblEstado);

        JLabel lblTabla = new JLabel("Clientes registrados:");
        lblTabla.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblTabla.setBounds(20, 108, 220, 18);
        add(lblTabla);

        modeloTabla = new DefaultTableModel(
                new String[]{"Nombre", "Estado", "Sesiones", "Mensajes", "Última conexión"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tabla = new JTable(modeloTabla);
        tabla.setFont(new Font("Monospaced", Font.PLAIN, 12));
        tabla.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        tabla.setRowHeight(20);
        JScrollPane scrollTabla = new JScrollPane(tabla);
        scrollTabla.setBounds(20, 130, 550, 115);
        add(scrollTabla);

        JLabel lblLog = new JLabel("Log:");
        lblLog.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblLog.setBounds(20, 253, 60, 18);
        add(lblLog);

        logTxt = new JTextArea();
        logTxt.setEditable(false);
        logTxt.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollLog = new JScrollPane(logTxt);
        scrollLog.setBounds(20, 273, 550, 215);
        add(scrollLog);
    }

    // ── Arranque del servidor ─────────────────────────────────────────────────

    private void iniciarServidor(boolean esReinicio) {
        if (corriendo.get()) return;

        vigilanteActivo.set(false);
        detenerManual.set(false);
        corriendo.set(true);
        clientPool = Executors.newCachedThreadPool();

        SwingUtilities.invokeLater(() -> {
            btnIniciar.setEnabled(false);
            btnDetener.setEnabled(true);
            lblEstado.setText("Estado: EN EJECUCIÓN  (Puerto " + PORT + ")");
            lblEstado.setForeground(new Color(0, 140, 0));
        });

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                String ip = InetAddress.getLocalHost().getHostAddress();
                log(esReinicio
                        ? "🔄 [REINICIO #" + intentosReinicio.get() + "] Servidor reiniciado → " + ip + ":" + PORT
                        : "✅ Servidor iniciado → " + ip + ":" + PORT);
                intentosReinicio.set(0);

                while (corriendo.get()) {
                    Socket cliente = serverSocket.accept();
                    log("🔗 Conexión entrante: " + cliente.getRemoteSocketAddress());
                    clientPool.submit(() -> manejarCliente(cliente));
                }

            } catch (IOException ex) {
                if (!detenerManual.get()) {
                    log("⚠️  Caída inesperada: " + ex.getMessage());
                    corriendo.set(false);
                    marcarTodosDesconectados();
                    politicaReinicioPorCaida();
                } else {
                    log("🛑 Servidor detenido manualmente.");
                    iniciarVigilante("Vigilante activo tras detención manual.");
                }
            } finally {
                corriendo.set(false);
                cerrarRecursos();
                SwingUtilities.invokeLater(() -> {
                    btnIniciar.setEnabled(true);
                    btnDetener.setEnabled(false);
                });
            }
        }, "hilo-servidor").start();
    }

    // ── Handshake + comunicación ──────────────────────────────────────────────

    private void manejarCliente(Socket socket) {
        String nombre = null;
        try (
                BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter    out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            out.println("INGRESA_NOMBRE");
            nombre = in.readLine();
            if (nombre == null || nombre.isBlank()) {
                out.println("ERROR:nombre_invalido");
                return;
            }
            nombre = nombre.trim();

            InfoCliente info    = registro.computeIfAbsent(nombre, InfoCliente::new);
            boolean     esNuevo = info.sesiones == 0;

            info.sesiones++;
            info.conectado      = true;
            info.ultimaConexion = LocalTime.now().format(HORA);

            if (esNuevo) {
                log("👤 Nuevo cliente: [" + nombre + "]  " + socket.getRemoteSocketAddress());
                out.println("BIENVENIDO_NUEVO:" + nombre);
            } else {
                log("🔁 Reconexión: [" + nombre + "]  sesión #" + info.sesiones);
                out.println("BIENVENIDO_DE_NUEVO:" + nombre + ":sesion=" + info.sesiones);
            }
            actualizarTabla();

            String linea;
            while ((linea = in.readLine()) != null) {
                info.mensajes++;
                log("← [" + nombre + "] " + linea);
                out.println("ACK: " + linea);
                actualizarTabla();
            }

        } catch (IOException ex) {
            if (corriendo.get())
                log("❌ Error con [" + (nombre != null ? nombre : "?") + "]: " + ex.getMessage());
        } finally {
            if (nombre != null && registro.containsKey(nombre)) {
                registro.get(nombre).conectado = false;
                actualizarTabla();
            }
            log("🔌 Desconectado: [" + (nombre != null ? nombre : "?") + "]");
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── Política de reinicio por caída ────────────────────────────────────────

    private void politicaReinicioPorCaida() {
        int intento = intentosReinicio.incrementAndGet();
        if (intento > MAX_REINTENTOS) {
            log("🚫 Máximo de reinicios por caída (" + MAX_REINTENTOS + ") alcanzado.");
            iniciarVigilante("Vigilante activo tras agotar reinicios por caída.");
            return;
        }
        log("🔁 Reinicio por caída " + intento + "/" + MAX_REINTENTOS
                + " → esperando " + DELAY_REINICIO_SEG + "s…");
        setEstado("Estado: REINICIANDO por caída (" + intento + "/" + MAX_REINTENTOS + ")",
                new Color(180, 100, 0));

        new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(DELAY_REINICIO_SEG);
                iniciarServidor(true);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "hilo-reinicio-caida").start();
    }

    // ── Vigilante ─────────────────────────────────────────────────────────────

    /**
     * Abre un ServerSocket temporal en el mismo puerto.
     * Al detectar la primera conexión entrante:
     *   1. Cierra la conexión del vigilante y libera el puerto.
     *   2. Espera 300ms para asegurar liberación del puerto.
     *   3. Arranca el servidor real completo.
     * El cliente, que estaba reintentando, conectará en su siguiente intento.
     */
    private void iniciarVigilante(String motivo) {
        if (vigilanteActivo.getAndSet(true)) return; // solo uno a la vez

        log("👁️  " + motivo);
        setEstado("Estado: DETENIDO – vigilante escuchando en puerto " + PORT, Color.DARK_GRAY);
        SwingUtilities.invokeLater(() -> {
            btnIniciar.setEnabled(true);
            btnDetener.setEnabled(false);
        });

        new Thread(() -> {
            ServerSocket sv = null;
            try {
                sv = new ServerSocket();
                sv.setReuseAddress(true);
                sv.bind(new InetSocketAddress(PORT));
                log("👁️  Vigilante listo. Esperando primer cliente en puerto " + PORT + "…");

                Socket primer = sv.accept(); // bloquea hasta que llega alguien
                log("👁️  Vigilante detectó cliente: " + primer.getRemoteSocketAddress()
                        + "  → reiniciando servidor…");

                try { primer.close(); } catch (IOException ignored) {}
                try { sv.close();    } catch (IOException ignored) {}
                sv = null;

                TimeUnit.MILLISECONDS.sleep(300); // asegurar liberación del puerto

                vigilanteActivo.set(false);
                intentosReinicio.set(0);
                iniciarServidor(true);

            } catch (IOException ex) {
                // Solo loguear si el vigilante no fue cancelado intencionalmente
                if (vigilanteActivo.get())
                    log("👁️  Vigilante detenido inesperadamente: " + ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                vigilanteActivo.set(false);
                if (sv != null) try { sv.close(); } catch (IOException ignored) {}
            }
        }, "hilo-vigilante").start();
    }

    // ── Detención manual ──────────────────────────────────────────────────────

    private void detenerManualmente() {
        if (!corriendo.get()) return;
        detenerManual.set(true);
        corriendo.set(false);
        marcarTodosDesconectados();
        cerrarRecursos();
        // El vigilante se lanza desde el catch del hilo-servidor
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    private void cerrarRecursos() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        if (clientPool != null) clientPool.shutdownNow();
    }

    private void marcarTodosDesconectados() {
        registro.values().forEach(i -> i.conectado = false);
        actualizarTabla();
    }

    private void actualizarTabla() {
        SwingUtilities.invokeLater(() -> {
            modeloTabla.setRowCount(0);
            for (InfoCliente info : registro.values()) {
                modeloTabla.addRow(new Object[]{
                        info.nombre,
                        info.conectado ? "🟢 Conectado" : "🔴 Desconectado",
                        info.sesiones,
                        info.mensajes,
                        info.ultimaConexion
                });
            }
        });
    }

    private void setEstado(String texto, Color color) {
        SwingUtilities.invokeLater(() -> {
            lblEstado.setText(texto);
            lblEstado.setForeground(color);
        });
    }

    private void log(String msg) {
        String ts = "[" + LocalTime.now().format(HORA) + "] ";
        SwingUtilities.invokeLater(() -> {
            logTxt.append(ts + msg + "\n");
            logTxt.setCaretPosition(logTxt.getDocument().getLength());
        });
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PrincipalSrv().setVisible(true));
    }
}