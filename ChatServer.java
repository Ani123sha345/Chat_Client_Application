import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * ChatServer.java — Multi-client TCP chat server on port 5000
 *
 * Compile:  javac ChatServer.java
 * Run:      java ChatServer [port]
 *
 * Protocol (line-based, UTF-8):
 *   Client → Server:
 *     NICK <name>           — register / rename (required first)
 *     <message>             — broadcast to all
 *     @<user> <message>     — private message
 *     /list                 — list online users
 *     /nick <newname>       — change nickname
 *     /quit                 — disconnect
 *     /help                 — command reference
 *
 *   Server → Client:
 *     [HH:mm:ss] SERVER: …  — system notices
 *     [HH:mm:ss] <nick>: …  — public message
 *     [HH:mm:ss] *A→B*: …   — private message
 *     ERROR …               — protocol error
 *     OK …                  — acknowledgement
 */
public class ChatServer {

    private static final int    DEFAULT_PORT  = 5000;
    private static final int    MAX_NICK_LEN  = 20;
    private static final String ENCODING      = "UTF-8";

    // nick.toLowerCase() → ClientHandler
    private final Map<String, ClientHandler> nickMap = new ConcurrentHashMap<>();
    // all connected clients (including not-yet-nicked)
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final ReadWriteLock registryLock = new ReentrantReadWriteLock();

    // ------------------------------------------------------------------ //
    //  Timestamp helper                                                    //
    // ------------------------------------------------------------------ //
    static String ts() {
        return new SimpleDateFormat("[HH:mm:ss]").format(new Date());
    }

    // ------------------------------------------------------------------ //
    //  Nick registry                                                       //
    // ------------------------------------------------------------------ //

    /** Returns "OK" on success, or an error string. */
    String registerNick(ClientHandler client, String newNick) {
        String key = newNick.toLowerCase();
        registryLock.writeLock().lock();
        try {
            ClientHandler existing = nickMap.get(key);
            if (existing != null && existing != client) {
                return "Nickname '" + newNick + "' is already taken.";
            }
            // Remove old entry when renaming
            if (client.nick != null) {
                nickMap.remove(client.nick.toLowerCase());
            }
            client.nick = newNick;
            nickMap.put(key, client);
            return "OK";
        } finally {
            registryLock.writeLock().unlock();
        }
    }

    void unregister(ClientHandler client) {
        registryLock.writeLock().lock();
        try {
            clients.remove(client);
            if (client.nick != null) {
                nickMap.remove(client.nick.toLowerCase());
            }
        } finally {
            registryLock.writeLock().unlock();
        }
    }

    ClientHandler getClient(String nick) {
        registryLock.readLock().lock();
        try {
            return nickMap.get(nick.toLowerCase());
        } finally {
            registryLock.readLock().unlock();
        }
    }

    List<String> listNicks() {
        registryLock.readLock().lock();
        try {
            List<String> names = new ArrayList<>();
            for (ClientHandler c : clients) {
                if (c.nick != null) names.add(c.nick);
            }
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            return names;
        } finally {
            registryLock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------ //
    //  Broadcast                                                           //
    // ------------------------------------------------------------------ //

    void broadcast(String msg, ClientHandler exclude) {
        for (ClientHandler c : clients) {
            if (c != exclude && c.nick != null) {
                c.enqueue(msg);
            }
        }
    }

    void broadcast(String msg) { broadcast(msg, null); }

    // ------------------------------------------------------------------ //
    //  Accept loop                                                         //
    // ------------------------------------------------------------------ //

    void serveForever(int port) throws IOException {
        ServerSocket srv = new ServerSocket(port);
        srv.setReuseAddress(true);
        System.out.printf("Chat server listening on port %d%n", port);
        try {
            while (true) {
                Socket sock = srv.accept();
                System.out.printf("New connection from %s%n", sock.getRemoteSocketAddress());
                ClientHandler handler = new ClientHandler(sock, this);
                clients.add(handler);
                handler.start();
            }
        } catch (IOException e) {
            System.out.println("Server shutting down.");
        } finally {
            srv.close();
        }
    }

    // ------------------------------------------------------------------ //
    //  Main                                                                //
    // ------------------------------------------------------------------ //

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new ChatServer().serveForever(port);
    }

    // ================================================================== //
    //  ClientHandler — one per connected socket                           //
    // ================================================================== //

    static class ClientHandler extends Thread {

        final Socket sock;
        final ChatServer server;
        volatile String nick = null;

        // Unbounded queue; null is the shutdown sentinel
        private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();
        private PrintWriter out;

        ClientHandler(Socket sock, ChatServer server) {
            this.sock   = sock;
            this.server = server;
            setDaemon(true);
        }

        // -------------------------------------------------------------- //
        //  Thread entry                                                    //
        // -------------------------------------------------------------- //

        @Override
        public void run() {
            // Start dedicated sender thread
            Thread sender = new Thread(this::senderWorker, "sender-" + getId());
            sender.setDaemon(true);
            sender.start();

            try {
                out = new PrintWriter(
                    new OutputStreamWriter(sock.getOutputStream(), ChatServer.ENCODING), true);

                enqueue("OK Welcome! Send NICK <yourname> to register. Type /help for commands.");

                BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), ChatServer.ENCODING));

                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) dispatch(line);
                }
            } catch (IOException ignored) {
            } finally {
                cleanup();
            }
        }

        // -------------------------------------------------------------- //
        //  Sender worker — drains sendQueue                               //
        // -------------------------------------------------------------- //

        private void senderWorker() {
            try {
                while (true) {
                    String msg = sendQueue.take();
                    if (msg == null) break;   // sentinel
                    if (out != null) out.println(msg);
                }
            } catch (InterruptedException ignored) {}
        }

        void enqueue(String msg) {
            sendQueue.offer(msg);
        }

        // -------------------------------------------------------------- //
        //  Protocol dispatch                                               //
        // -------------------------------------------------------------- //

        private void dispatch(String line) {
            // Registration (first action required)
            if (line.length() >= 5 && line.substring(0, 5).equalsIgnoreCase("NICK ")) {
                cmdNick(line.substring(5).trim());
                return;
            }

            if (nick == null) {
                enqueue("ERROR You must register with NICK <yourname> first.");
                return;
            }

            // Slash commands
            if (line.startsWith("/")) {
                String[] parts = line.split("\\s+", 2);
                String cmd = parts[0].toLowerCase();
                String arg = parts.length > 1 ? parts[1].trim() : "";

                switch (cmd) {
                    case "/quit" -> cmdQuit();
                    case "/list" -> cmdList();
                    case "/nick" -> cmdNick(arg);
                    case "/help" -> cmdHelp();
                    default      -> enqueue("ERROR Unknown command: " + cmd);
                }
                return;
            }

            // Private message  @user text
            if (line.startsWith("@")) {
                int space = line.indexOf(' ');
                if (space == -1) {
                    enqueue("ERROR Usage: @<user> <message>");
                    return;
                }
                cmdPrivate(line.substring(1, space), line.substring(space + 1).trim());
                return;
            }

            // Public broadcast
            String msg = ts() + " " + nick + ": " + line;
            server.broadcast(msg, this);
            enqueue(msg);   // echo to sender
            System.out.printf("BROADCAST <%s>: %s%n", nick, line);
        }

        // -------------------------------------------------------------- //
        //  Commands                                                        //
        // -------------------------------------------------------------- //

        private void cmdNick(String newNick) {
            if (newNick.isEmpty()) {
                enqueue("ERROR Usage: NICK <yourname>");
                return;
            }
            if (newNick.length() > ChatServer.MAX_NICK_LEN) {
                enqueue("ERROR Nickname too long (max " + ChatServer.MAX_NICK_LEN + " chars).");
                return;
            }
            if (!newNick.matches("[A-Za-z0-9_\\-]+")) {
                enqueue("ERROR Nickname may only contain letters, digits, - and _.");
                return;
            }
            String oldNick = nick;
            String result  = server.registerNick(this, newNick);
            if (!result.equals("OK")) {
                enqueue("ERROR " + result);
                return;
            }
            enqueue("OK You are now known as " + newNick + ".");
            if (oldNick == null) {
                server.broadcast(ts() + " SERVER: " + newNick + " joined the chat.", this);
                System.out.printf("JOINED: %s from %s%n", newNick, sock.getRemoteSocketAddress());
            } else {
                server.broadcast(
                    ts() + " SERVER: " + oldNick + " is now known as " + newNick + ".", this);
                System.out.printf("RENAME: %s → %s%n", oldNick, newNick);
            }
        }

        private void cmdQuit() {
            enqueue("OK Goodbye!");
            sendQueue.offer(null);  // flush then stop sender
            try { sock.shutdownOutput(); } catch (IOException ignored) {}
        }

        private void cmdList() {
            List<String> users = server.listNicks();
            if (users.isEmpty()) {
                enqueue("OK No users online.");
            } else {
                enqueue("OK Online (" + users.size() + "): " + String.join(", ", users));
            }
        }

        private void cmdPrivate(String targetNick, String text) {
            if (text.isEmpty()) {
                enqueue("ERROR Cannot send an empty private message.");
                return;
            }
            ClientHandler target = server.getClient(targetNick);
            if (target == null) {
                enqueue("ERROR No user named '" + targetNick + "' is online.");
                return;
            }
            if (target == this) {
                enqueue("ERROR You cannot message yourself.");
                return;
            }
            target.enqueue(ts() + " *" + nick + "→" + targetNick + "*: " + text);
            enqueue(ts() + " *you→" + targetNick + "*: " + text);
            System.out.printf("PM <%s> → <%s>: %s%n", nick, targetNick, text);
        }

        private void cmdHelp() {
            enqueue("OK Commands:");
            enqueue("  NICK <name>          — set / change your nickname");
            enqueue("  <message>            — broadcast to everyone");
            enqueue("  @<user> <message>    — private message");
            enqueue("  /list                — show online users");
            enqueue("  /nick <newname>      — change nickname");
            enqueue("  /quit                — disconnect");
            enqueue("  /help                — this help text");
        }

        // -------------------------------------------------------------- //
        //  Cleanup on disconnect                                           //
        // -------------------------------------------------------------- //

        private void cleanup() {
            server.unregister(this);
            sendQueue.offer(null);  // stop sender
            try { sock.close(); } catch (IOException ignored) {}
            if (nick != null) {
                server.broadcast(ts() + " SERVER: " + nick + " left the chat.");
                System.out.printf("DISCONNECTED: %s from %s%n", nick, sock.getRemoteSocketAddress());
            } else {
                System.out.printf("DISCONNECTED (no nick): %s%n", sock.getRemoteSocketAddress());
            }
        }
    }
}
