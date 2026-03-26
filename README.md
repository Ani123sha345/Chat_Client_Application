# Chat_Client_Application
# ☕ Java Socket Chat

A terminal-based multi-client chat system over TCP, built with **pure Java**.  
No external libraries. Supports public messages, private DMs, nicknames, and live timestamps.

![Java](https://img.shields.io/badge/Java-14%2B-orange)
![TCP](https://img.shields.io/badge/Protocol-TCP%20Sockets-blue)
![Threads](https://img.shields.io/badge/Multi--Threaded-yes-green)
![No Libs](https://img.shields.io/badge/External%20Libs-none-lightgrey)

---

## 📁 Project Structure
```
JavaSocketChat/
├── ChatServer.java   # Run once — listens on port 5000, manages all clients
└── ChatClient.java   # Run one per user — connects and chats interactively
```

---

## ⚙️ Requirements

- Java **14 or higher**
- Check your version:
```bash
java -version
```

---

## 🚀 Quick Start

### Step 1 — Compile (once)
```bash
javac ChatServer.java ChatClient.java
```

### Step 2 — Start the Server (Terminal 1)
```bash
java ChatServer
```

You should see:
```
Chat server listening on port 5000
```

### Step 3 — Connect Clients (Terminal 2, 3, 4…)
```bash
java ChatClient
```

Connect to a remote host:
```bash
java ChatClient 192.168.1.10 5000
```

Custom port:
```bash
java ChatServer 6000
java ChatClient localhost 6000
```

---

## 💬 Chat Session Example
```
Connected to 127.0.0.1:5000
OK Welcome! Send NICK <yourname> to register. Type /help for commands.

> NICK alice
OK You are now known as alice.

alice> Hello everyone!
[10:32:14] alice: Hello everyone!

alice> @bob Hey, private message!
[10:32:20] *you→bob*: Hey, private message!

alice> /list
OK Online (2): alice, bob

alice> /nick ally
OK You are now known as ally.

ally> /quit
OK Goodbye!
```

---

## 📋 Protocol Reference

| Command | Action |
|---|---|
| `NICK <name>` | Register or rename yourself — **required first step** |
| `<message>` | Broadcast to all online users |
| `@<user> <message>` | Send a private message to one user |
| `/list` | Show all currently online users |
| `/nick <newname>` | Change your nickname mid-session |
| `/help` | Print command reference in terminal |
| `/quit` | Gracefully disconnect |

---

## 🏗️ Architecture

### ChatServer.java

| Component | Detail |
|---|---|
| `ServerSocket` accept loop | Spawns one `ClientHandler` thread per connection |
| Nick registry | `ConcurrentHashMap` + `ReentrantReadWriteLock` — thread-safe rename, join, and disconnect |
| `broadcast()` | Delivers a message to all registered clients except the sender |

### ClientHandler (inner class inside ChatServer)

| Component | Detail |
|---|---|
| Reader thread | Main handler thread — reads lines, calls `dispatch()` |
| Sender thread | Dedicated `senderWorker` drains a `LinkedBlockingQueue<String>` |
| `null` sentinel | Enqueuing `null` stops the sender thread gracefully |
| `cleanup()` | Frees nick, notifies all users, closes socket |

### ChatClient.java

| Component | Detail |
|---|---|
| `ReceiverThread` | Daemon thread — prints server messages, auto-updates the prompt |
| Main thread | Reads `stdin`, sends lines to server |
| ANSI prompt | `\r\033[K` clears the line before printing incoming messages |
| Shutdown hook | Catches `Ctrl+C`, sends `/quit` for a clean disconnect |

---

## 🔄 Message Flow

**Public broadcast:**
```
Client A types → Socket stream → dispatch() → broadcast() → All clients' queues
```

**Private message:**
```
@bob hello → getClient("bob") → bob.enqueue() → bob's terminal only
```

> **Why a per-client queue?**  
> If one client's socket is slow or blocked, writing to it directly would stall
> the reader thread and freeze the whole server. The `BlockingQueue` + dedicated
> sender thread fully decouples reading from writing.

---

## ⚠️ Common Errors

| Error | Fix |
|---|---|
| `Connection refused` | Start `ChatServer` first, then connect clients |
| `Address already in use` | Port 5000 is busy — use `java ChatServer 6000` |
| `release version not supported` | Requires Java 14+. Check with `java -version` |
| `Nickname already taken` | Choose a different nick — uniqueness is case-insensitive |
| `ERROR You must register first` | Send `NICK yourname` before any other command |

---

## 🔒 Nick Rules

- Letters, digits, `-` and `_` only
- Max **20 characters**
- Case-insensitive uniqueness (`Alice` and `alice` are the same)
- Can be changed any time with `/nick <newname>`

---

## 📡 Server Output Log

The server prints a live log to its terminal:
```
Chat server listening on port 5000
New connection from /127.0.0.1:52341
JOINED: alice from /127.0.0.1:52341
BROADCAST <alice>: Hello everyone!
PM <alice> → <bob>: Hey there
RENAME: alice → ally
DISCONNECTED: ally from /127.0.0.1:52341
```

---

## 📄 License

MIT — free to use, modify, and distribute.
