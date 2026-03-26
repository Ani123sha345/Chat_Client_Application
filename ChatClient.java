import java.io.*;
import java.net.*;

/**
 * ChatClient.java — connects to the chat server
 *
 * Compile:  javac ChatClient.java
 * Run:      java ChatClient [host] [port]
 *
 * Two threads:
 *   • ReceiverThread — reads lines from server and prints them
 *   • Main thread    — reads stdin, sends lines to server
 *
 * The prompt is reprinted after each incoming message so the
 * terminal stays tidy during active conversations.
 */
public class ChatClient {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int    DEFAULT_PORT = 5000;
    private static final String ENCODING     = "UTF-8";

    private volatile boolean running = false;
    private volatile String  nick    = "";
    private Socket           sock;
    private PrintWriter      out;

    // ------------------------------------------------------------------ //
    //  Prompt helper                                                       //
    // ------------------------------------------------------------------ //

    private String prompt() {
        return nick.isEmpty() ? "> " : nick + "> ";
    }

    /** Erase current line, print msg, then reprint the prompt. */
    private synchronized void printMsg(String msg) {
        System.out.print("\r\033[K");   // ANSI: carriage return + erase line
        System.out.println(msg);
        System.out.print(prompt());
        System.out.flush();
    }

    // ------------------------------------------------------------------ //
    //  Receiver thread                                                     //
    // ------------------------------------------------------------------ //

    private class ReceiverThread extends Thread {
        ReceiverThread() {
            setDaemon(true);
            setName("receiver");
        }

        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), ENCODING));
                String line;
                while ((line = in.readLine()) != null) {
                    // Track nickname for the prompt
                    if (line.startsWith("OK You are now known as ")) {
                        nick = line.substring("OK You are now known as ".length())
                                   .replaceAll("\\.$", "").trim();
                    }
                    printMsg(line);
                    if (line.equals("OK Goodbye!")) {
                        running = false;
                        break;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (running) {
                    System.out.println("\n[Disconnected from server]");
                }
                running = false;
                try { sock.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Connect + run                                                       //
    // ------------------------------------------------------------------ //

    void connect(String host, int port) throws IOException {
        sock = new Socket(host, port);
        out  = new PrintWriter(
            new OutputStreamWriter(sock.getOutputStream(), ENCODING), true);
        running = true;
        System.out.printf("Connected to %s:%d%n", host, port);
        System.out.println("Type /help for available commands.\n");
    }

    void run() {
        new ReceiverThread().start();

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

        // Register shutdown hook for Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) {
                try { out.println("/quit"); Thread.sleep(300); }
                catch (InterruptedException ignored) {}
            }
        }));

        try {
            while (running) {
                System.out.print(prompt());
                System.out.flush();

                String line;
                try {
                    line = stdin.readLine();
                } catch (IOException e) {
                    break;
                }

                if (line == null) {
                    // EOF (Ctrl+D)
                    line = "/quit";
                }

                line = line.trim();
                if (line.isEmpty()) {
                    // Erase the blank prompt line
                    System.out.print("\033[A\r\033[K");
                    continue;
                }

                if (!running) break;
                out.println(line);
            }
        } finally {
            running = false;
            try { sock.close(); } catch (IOException ignored) {}
            System.out.println("\nGoodbye!");
        }
    }

    // ------------------------------------------------------------------ //
    //  Main                                                                //
    // ------------------------------------------------------------------ //

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int    port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        ChatClient client = new ChatClient();
        try {
            client.connect(host, port);
        } catch (ConnectException e) {
            System.err.printf("ERROR: Could not connect to %s:%d — is the server running?%n",
                              host, port);
            System.exit(1);
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
        client.run();
    }
}
