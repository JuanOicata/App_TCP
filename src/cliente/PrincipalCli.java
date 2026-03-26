package cliente;

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
 * Cliente TCP con dos fases de reconexión:
 *
 *  FASE 1 – Reintentos rápidos:
 *    Hasta MAX_REINTENTOS intentos, cada DELAY_RAPIDO_S segundos.
 *    Cubre el caso: servidor se acaba de caer, debería volver pronto.
 *
 *  FASE 2 – Espera pasiva (infinita):
 *    Si se agotan los reintentos rápidos, el cliente NO abandona.
 *    Sigue intentando cada DELAY_PASIVO_S segundos indefinidamente,
 *    esperando que el servidor (o su vigilante) vuelva a estar activo.
 *    El usuario puede cancelar manualmente con DESCONECTAR.
 */
public class PrincipalCli extends JFrame {

    // ── Configuración ────────────────────────────────────────────────────────
    private static final String HOST            = "localhost";
    private static final int    PORT            = 12345;
    private static final int    MAX_REINTENTOS  = 10;       // reintentos rápidos
    private static final int    TIMEOUT_CONN_MS = 3_000;    // timeout por intento
    private static final int    DELAY_RAPIDO_S  = 4;        // espera entre reintentos rápidos
    private static final int    DELAY_PASIVO_S  = 15;       // espera en modo pasivo
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Identidad ─────────────────────────────────────────────────────────────
    private final String nombre;

    // ── Estado ───────────────────────────────────────────────────────────────
    private Socket         socket;
    private PrintWriter    out;
    private BufferedReader in;

    private final AtomicBoolean conectado   = new AtomicBoolean(false);
    private final AtomicBoolean detener     = new AtomicBoolean(false);
    private final AtomicInteger intentos    = new AtomicInteger(0);
    /** true = ya agotó reintentos rápidos, está en espera pasiva */
    private final AtomicBoolean modoPasivo  = new AtomicBoolean(false);

    // ── GUI ───────────────────────────────────────────────────────────────────
    private JButton    btnConectar, btnDesconectar, btnEnviar;
    private JTextArea  logTxt;
    private JTextField mensajeTxt;
    private JLabel     lblEstado;

    // ─────────────────────────────────────────────────────────────────────────
    public PrincipalCli(String nombre) {
        this.nombre = nombre;
        initComponents();
    }

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

        JLabel lblNombre = new JLabel("Identidad:  " + nombre);
        lblNombre.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblNombre.setForeground(new Color(0, 100, 0));
        lblNombre.setBounds(20, 36, 460, 18);
        add(lblNombre);

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

        lblEstado = new JLabel("Estado: DESCONECTADO");
        lblEstado.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblEstado.setForeground(Color.DARK_GRAY);
        lblEstado.setBounds(20, 103, 460, 18);
        add(lblEstado);

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
        modoPasivo.set(false);
        intentos.set(0);
        setBotonConectar(false);
        // Habilitar DESCONECTAR para que el usuario pueda cancelar en cualquier momento
        SwingUtilities.invokeLater(() -> btnDesconectar.setEnabled(true));
        new Thread(this::cicloConexion, "hilo-cx-" + nombre).start();
    }

    /**
     * Ciclo de conexión con dos fases:
     *   - Fase 1: reintentos rápidos (limitados).
     *   - Fase 2: espera pasiva infinita hasta que el usuario cancele.
     */
    private void cicloConexion() {
        while (!detener.get()) {

            // ── Determinar delay y etiqueta según la fase actual ──────────────
            boolean esPasivo = modoPasivo.get();
            int     delay    = esPasivo ? DELAY_PASIVO_S : DELAY_RAPIDO_S;

            if (!esPasivo) {
                int intento = intentos.incrementAndGet();

                // Transición a modo pasivo al agotar reintentos rápidos
                if (intento > MAX_REINTENTOS) {
                    modoPasivo.set(true);
                    log("⏸️  Reintentos rápidos agotados. Entrando en espera pasiva "
                            + "(cada " + DELAY_PASIVO_S + "s). Pulsa DESCONECTAR para cancelar.");
                    setEstado("Estado: ESPERA PASIVA – intentando cada " + DELAY_PASIVO_S + "s",
                            new Color(120, 0, 120));
                    intentos.set(0);
                    continue; // volver al inicio del while ya en modo pasivo
                }

                log("🔄 Intento rápido " + intento + "/" + MAX_REINTENTOS
                        + "  →  " + HOST + ":" + PORT
                        + "  (timeout " + TIMEOUT_CONN_MS + "ms)");
                setEstado("Estado: CONECTANDO… (" + intento + "/" + MAX_REINTENTOS + ")",
                        new Color(180, 100, 0));
            } else {
                log("🔄 Espera pasiva → intentando " + HOST + ":" + PORT
                        + "  (timeout " + TIMEOUT_CONN_MS + "ms)");
            }

            // ── Intento de conexión ───────────────────────────────────────────
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_CONN_MS);
                out = new PrintWriter(socket.getOutputStream(), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // ── Handshake ──
                String primera = in.readLine();
                if ("INGRESA_NOMBRE".equals(primera)) {
                    out.println(nombre);
                    String respuesta = in.readLine();
                    if (respuesta != null && respuesta.startsWith("BIENVENIDO_DE_NUEVO")) {
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

                // ── Conexión exitosa: resetear estado ──
                conectado.set(true);
                modoPasivo.set(false);
                intentos.set(0);
                setEstado("Estado: CONECTADO  →  " + HOST + ":" + PORT, new Color(0, 140, 0));
                setEnvioHabilitado(true);

                escucharServidor(); // bloquea hasta que cae la conexión

                // ── Conexión perdida ──
                setEnvioHabilitado(false);
                conectado.set(false);

                if (detener.get()) {
                    log("🛑 Desconectado voluntariamente.");
                    break;
                }

                log("⚠️  Conexión perdida. Iniciando reintentos rápidos…");
                setEstado("Estado: RECONECTANDO…", new Color(180, 100, 0));
                modoPasivo.set(false);
                intentos.set(0);
                continue; // ir directo al siguiente intento sin esperar

            } catch (SocketTimeoutException ste) {
                log("⏱️  Timeout" + (esPasivo ? " (pasivo)" : " intento " + intentos.get())
                        + ": servidor no responde en " + TIMEOUT_CONN_MS + "ms");
            } catch (ConnectException ce) {
                log("❌ " + (esPasivo ? "[Pasivo]" : "Intento " + intentos.get())
                        + " fallido: " + ce.getMessage());
            } catch (IOException ex) {
                log("❌ Error E/S: " + ex.getMessage());
            }

            // ── Esperar antes del próximo intento ─────────────────────────────
            if (!detener.get()) {
                log("⏳ Esperando " + delay + "s…");
                try { TimeUnit.SECONDS.sleep(delay); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        // ── Limpieza final ────────────────────────────────────────────────────
        cerrarSocket();
        setEnvioHabilitado(false);
        setBotonConectar(true);
        setEstado("Estado: DESCONECTADO", Color.DARK_GRAY);
        SwingUtilities.invokeLater(() -> btnDesconectar.setEnabled(false));
    }

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
    }

    private void enviarMensaje() {
        if (!conectado.get() || out == null) return;
        String texto = mensajeTxt.getText().trim();
        if (texto.isEmpty()) return;

        out.println(texto);

        if (out.checkError()) {
            log("⚠️  No se pudo enviar: conexión perdida.");
            conectado.set(false);
            setEnvioHabilitado(false);
            setEstado("Estado: RECONECTANDO…", new Color(180, 100, 0));
            cerrarSocket();
            return;
        }

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

    // ── Utilidades UI ─────────────────────────────────────────────────────────

    private void setEnvioHabilitado(boolean h) {
        SwingUtilities.invokeLater(() -> {
            mensajeTxt.setEnabled(h);
            btnEnviar.setEnabled(h);
        });
    }

    private void setBotonConectar(boolean h) {
        SwingUtilities.invokeLater(() -> btnConectar.setEnabled(h));
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
        SwingUtilities.invokeLater(() -> {
            String nombre = null;
            while (nombre == null || nombre.isBlank()) {
                nombre = JOptionPane.showInputDialog(
                        null, "Ingresa tu nombre de cliente:",
                        "Identificación de Cliente", JOptionPane.QUESTION_MESSAGE);
                if (nombre == null) System.exit(0);
                nombre = nombre.trim();
                if (nombre.isBlank())
                    JOptionPane.showMessageDialog(null, "El nombre no puede estar vacío.");
            }
            new PrincipalCli(nombre).setVisible(true);
        });
    }
}