package org.vinni.cliente.gui;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente TCP con:
 *  - Nombre de identidad: se pide al iniciar la app y persiste en reconexiones.
 *  - Instancia única por ejecución (un cliente = una ventana).
 *  - Políticas de reintentos, timeout y reconexión automática.
 *  - Envío deshabilitado cuando no hay conexión activa.
 */
public class PrincipalCli extends JFrame {

    // ── Configuración ────────────────────────────────────────────────────────
    private static final String HOST              = "localhost";
    private static final int    PORT              = 12345;
    private static final int    MAX_REINTENTOS    = 10;
    private static final int    TIMEOUT_CONN_MS   = 3_000;
    private static final int    DELAY_REINTENTO_S = 4;
    private static final DateTimeFormatter HORA   = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Identidad del cliente ─────────────────────────────────────────────────
    private final String nombre;   // fijo durante toda la ejecución

    // ── Estado ───────────────────────────────────────────────────────────────
    private Socket         socket;
    private PrintWriter    out;
    private BufferedReader in;

    private final AtomicBoolean conectado      = new AtomicBoolean(false);
    private final AtomicBoolean detener        = new AtomicBoolean(false);
    private final AtomicInteger intentos       = new AtomicInteger(0);

    // ── Componentes GUI ──────────────────────────────────────────────────────
    private JButton    btnConectar, btnDesconectar, btnEnviar;
    private JTextArea  logTxt;
    private JTextField mensajeTxt;
    private JLabel     lblEstado, lblNombre;

    // ─────────────────────────────────────────────────────────────────────────
    public PrincipalCli(String nombre) {
        this.nombre = nombre;
        initComponents();
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    private void initComponents() {
        setTitle("Cliente TCP  [" + nombre + "]");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(520, 450);
        setLocationRelativeTo(null);
        getContentPane().setLayout(null);

        JLabel titulo = new JLabel("CLIENTE TCP", SwingConstants.CENTER);
        titulo.setFont(new Font("Tahoma", Font.BOLD, 16));
        titulo.setForeground(new Color(0, 80, 160));
        titulo.setBounds(10, 8, 480, 22);
        add(titulo);

        // ── Nombre del cliente ──
        lblNombre = new JLabel("Identidad:  " + nombre);
        lblNombre.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblNombre.setForeground(new Color(0, 100, 0));
        lblNombre.setBounds(20, 36, 460, 18);
        add(lblNombre);

        // ── Botones ──
        btnConectar = new JButton("▶  CONECTAR");
        btnConectar.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        btnConectar.setBounds(20, 62, 145, 34);
        btnConectar.addActionListener(e -> iniciarConexion());
        add(btnConectar);

        btnDesconectar = new JButton("■  DESCONECTAR");
        btnDesconectar.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        btnDesconectar.setBounds(178, 62, 155, 34);
        btnDesconectar.setEnabled(false);
        btnDesconectar.addActionListener(e -> desconectarManualmente());
        add(btnDesconectar);

        // ── Estado ──
        lblEstado = new JLabel("Estado: DESCONECTADO");
        lblEstado.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblEstado.setForeground(Color.DARK_GRAY);
        lblEstado.setBounds(20, 103, 460, 18);
        add(lblEstado);

        // ── Envío de mensajes ──
        JLabel lbl = new JLabel("Mensaje:");
        lbl.setFont(new Font("Verdana", Font.PLAIN, 13));
        lbl.setBounds(20, 130, 90, 26);
        add(lbl);

        mensajeTxt = new JTextField();
        mensajeTxt.setFont(new Font("Verdana", Font.PLAIN, 13));
        mensajeTxt.setBounds(115, 130, 270, 26);
        mensajeTxt.setEnabled(false);
        mensajeTxt.addActionListener(e -> enviarMensaje());
        add(mensajeTxt);

        btnEnviar = new JButton("Enviar");
        btnEnviar.setFont(new Font("Verdana", Font.PLAIN, 13));
        btnEnviar.setBounds(395, 130, 90, 26);
        btnEnviar.setEnabled(false);
        btnEnviar.addActionListener(e -> enviarMensaje());
        add(btnEnviar);

        // ── Log ──
        logTxt = new JTextArea();
        logTxt.setEditable(false);
        logTxt.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(logTxt);
        scroll.setBounds(20, 168, 465, 240);
        add(scroll);
    }

    // ── Lógica de conexión ────────────────────────────────────────────────────

    private void iniciarConexion() {
        if (conectado.get()) return;
        detener.set(false);
        intentos.set(0);
        setBotonConectar(false);
        new Thread(this::cicloConexion, "hilo-cx-" + nombre).start();
    }

    /**
     * Ciclo principal de reintentos / reconexión.
     * Cuando la conexión se pierde (sin ser voluntaria) vuelve a reintentar.
     */
    private void cicloConexion() {
        while (!detener.get()) {

            int intento = intentos.incrementAndGet();
            if (intento > MAX_REINTENTOS) {
                log("🚫 Máximo de reintentos (" + MAX_REINTENTOS + ") alcanzado. Abandonando.");
                setEstado("Estado: AGOTÓ REINTENTOS", Color.RED);
                setBotonConectar(true);
                return;
            }

            log("🔄 Intento " + intento + "/" + MAX_REINTENTOS
                    + "  →  " + HOST + ":" + PORT
                    + "  (timeout " + TIMEOUT_CONN_MS + "ms)");
            setEstado("Estado: CONECTANDO… (" + intento + "/" + MAX_REINTENTOS + ")",
                    new Color(180, 100, 0));

            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_CONN_MS);
                out = new PrintWriter(socket.getOutputStream(), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // ── Handshake con el servidor ──
                String primera = in.readLine();           // espera "INGRESA_NOMBRE"
                if ("INGRESA_NOMBRE".equals(primera)) {
                    out.println(nombre);                  // envía el nombre registrado
                    String respuesta = in.readLine();     // BIENVENIDO_NUEVO o BIENVENIDO_DE_NUEVO
                    if (respuesta != null && respuesta.startsWith("BIENVENIDO_DE_NUEVO")) {
                        // Extraer número de sesión del mensaje "BIENVENIDO_DE_NUEVO:nombre:sesion=N"
                        String[] partes = respuesta.split(":");
                        String sesion = partes.length >= 3 ? partes[2] : "";
                        log("🔁 Reconectado como [" + nombre + "]  (" + sesion + ")");
                    } else {
                        log("👤 Registrado como nuevo cliente: [" + nombre + "]");
                    }
                } else if (primera != null && primera.startsWith("ERROR")) {
                    log("❌ Servidor rechazó la conexión: " + primera);
                    cerrarSocket();
                    break;
                }

                conectado.set(true);
                intentos.set(0);
                setEstado("Estado: CONECTADO  →  " + HOST + ":" + PORT, new Color(0, 140, 0));
                setEnvioHabilitado(true);

                escucharServidor();   // bloquea hasta que cae la conexión

                // ── Llegamos aquí: la conexión terminó ──
                setEnvioHabilitado(false);
                conectado.set(false);

                if (detener.get()) {
                    log("🛑 Desconectado voluntariamente.");
                    break;
                } else {
                    log("⚠️  Conexión perdida. Iniciando política de reconexión…");
                    setEstado("Estado: RECONECTANDO…", new Color(180, 100, 0));
                    intentos.set(0);  // reinicia contador para la nueva ronda
                }

            } catch (SocketTimeoutException ste) {
                log("⏱️  Timeout en intento " + intento + " (" + TIMEOUT_CONN_MS + "ms)");
            } catch (ConnectException ce) {
                log("❌ Intento " + intento + " fallido: " + ce.getMessage());
            } catch (IOException ex) {
                log("❌ Error E/S en intento " + intento + ": " + ex.getMessage());
            }

            // Esperar antes del próximo reintento
            if (!detener.get() && intentos.get() < MAX_REINTENTOS) {
                log("⏳ Esperando " + DELAY_REINTENTO_S + "s…");
                try { TimeUnit.SECONDS.sleep(DELAY_REINTENTO_S); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        // Limpieza final
        cerrarSocket();
        setEnvioHabilitado(false);
        setBotonConectar(true);
        setEstado("Estado: DESCONECTADO", Color.DARK_GRAY);
        SwingUtilities.invokeLater(() -> btnDesconectar.setEnabled(false));
    }

    /** Lee mensajes del servidor hasta que la conexión se cierra. */
    private void escucharServidor() {
        try {
            String linea;
            while ((linea = in.readLine()) != null) {
                log("← Servidor: " + linea);
            }
        } catch (IOException ex) {
            if (!detener.get())
                log("⚠️  Conexión interrumpida: " + ex.getMessage());
        }
        SwingUtilities.invokeLater(() -> {
            btnEnviar.setEnabled(false);
            mensajeTxt.setEnabled(false);
        });
    }

    private void enviarMensaje() {
        if (!conectado.get() || out == null) return;
        String texto = mensajeTxt.getText().trim();
        if (texto.isEmpty()) return;
        out.println(texto);
        log("→ Enviado: " + texto);
        mensajeTxt.setText("");
    }

    private void desconectarManualmente() {
        detener.set(true);
        conectado.set(false);
        cerrarSocket();
    }

    private void cerrarSocket() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    // ── Utilidades de UI ─────────────────────────────────────────────────────

    private void setEnvioHabilitado(boolean habilitado) {
        SwingUtilities.invokeLater(() -> {
            mensajeTxt.setEnabled(habilitado);
            btnEnviar.setEnabled(habilitado);
            btnDesconectar.setEnabled(habilitado);
        });
    }

    private void setBotonConectar(boolean habilitado) {
        SwingUtilities.invokeLater(() -> btnConectar.setEnabled(habilitado));
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

    // ── Main ─────────────────────────────────────────────────────────────────
    // Cada ejecución de este main = un cliente independiente.

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Pedir nombre antes de abrir la ventana principal
            String nombre = null;
            while (nombre == null || nombre.isBlank()) {
                nombre = JOptionPane.showInputDialog(
                        null,
                        "Ingresa tu nombre de cliente:",
                        "Identificación de Cliente",
                        JOptionPane.QUESTION_MESSAGE
                );
                if (nombre == null) System.exit(0); // usuario canceló
                nombre = nombre.trim();
                if (nombre.isBlank())
                    JOptionPane.showMessageDialog(null, "El nombre no puede estar vacío.");
            }
            new PrincipalCli(nombre).setVisible(true);
        });
    }
}