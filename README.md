# Chat_Client_Application
Java 14+
TCP Sockets
Multi-threaded
No external libs
Java Socket Chat
A terminal-based multi-client chat system over TCP, built with pure Java.
Supports public messages, private DMs, nicknames, and live timestamps.
Files
SRV
ChatServer.java
Run once. Listens on port 5000. Manages all clients, nicknames, and message routing.
CLI
ChatClient.java
Run one per user. Connects to the server, sends and receives messages interactively.
Quick start
Compile
Server
Client
Chat session
# Step 1 — compile both files (once) $ javac ChatServer.java ChatClient.java ✓ ChatServer.class ChatClient.class generated
Protocol reference
Command	Action
NICK <name>	Register or rename yourself — required first step
<message>	Broadcast to all online users
@<user> <msg>	Send a private message to one user
/list	Show all currently online users
/nick <new>	Change your nickname
/help	Print command reference in terminal
/quit	Gracefully disconnect
Architecture
ChatServer
Accept loop spawns one ClientHandler thread per connection. Owns the nick registry protected by a ReentrantReadWriteLock.
ServerSocket ConcurrentHashMap ReadWriteLock
ClientHandler
Two threads per client — a reader that dispatches protocol lines, and a sender that drains a BlockingQueue. Slow clients never block others.
Thread per client BlockingQueue null sentinel
ChatClient
ReceiverThread prints incoming messages and tracks nick for the prompt. Main thread reads stdin and sends lines.
ReceiverThread ANSI prompt ShutdownHook
Disconnect safety
On any disconnect, nick is freed, all clients notified, socket closed, and the sender thread stopped via a null sentinel in the queue.
Cleanup method No crash Nick freed
Message flow
Client A types
→
Socket stream
→
dispatch()
→
broadcast()
→
All clients' queues
Public message — delivered to every registered user except the sender, who gets an echo.
@bob hello
→
getClient("bob")
→
bob.enqueue()
Private message — routed directly to the target's send queue only.
Why a per-client queue? If one client's socket is slow or blocked, writing to it directly would stall the reader thread and freeze the whole server. The BlockingQueue + dedicated sender thread decouples reading from writing completely.
Common errors
Error	Fix
Connection refused	Start ChatServer first, then connect clients
Address already in use	Another process owns port 5000 — use java ChatServer 6000
release version not supported	Requires Java 14+. Check with java -version
Nickname already taken	Choose a different nick — case-insensitive uniqueness enforced
