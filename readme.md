# What is this project

This project is a java based peer to peer file sharing system which uses centralized tracker model where peers share files directly with each other, but central tracker server only keeps track of who has that.

## Architecture

### Tracker Server (Tracker.java,port 8000)

- So this central directory service does not store any files it only has the metadata.
It maintains a registry for each file such that which peer has which chunk is properly recognized.

- It handles mainly 3 commands register, search, update_chunk

- It uses concurrentHashMap + cached thread pool for the concurrent peers.

It has the data model fileInfo as follows :

```java
static class FileInfo {
    long fileSize;
    int chunkSize;
    Map<Integer, List<String>> chunkToPeersMap;
}
```

- it has fileSize - which will be total bytes in file
- chunksize - which shows how big each chunk is
- chunksToPeersMap - map from chunk index -> list of peer addresses.


This is how the file will look like in memory for the 3 chunk file

```
"lorem.txt" → FileInfo {
    fileSize: 786432,
    chunkSize: 262144,
    chunkToPeersMap: {
        0: ["127.0.0.1:6001"],
        1: ["127.0.0.1:6001", "127.0.0.1:6002"],  // peer 6002 already downloaded this
        2: ["127.0.0.1:6001"]
    }
}
```

Tracker does not store file itself, it only knows who has that chunk. Fileregistry is concurrentHashMap<String,FileInfo> which is threadsafe which helps multiple peers connect simultaneously.

### Main loop of the tracker :

```java
ExecutorService executor = Executors.newCachedThreadPool();
ServerSocket serverSocket = new ServerSocket(TRACKER_PORT);
while (true) {
    Socket peerSocket = serverSocket.accept();
    executor.submit(new PeerHandler(peerSocket));
}
```


In which newCachedThreadPool() which means that we create a new thread for each connection, and reuse idle ones. Every time a peer connects, it will get its own peerHandler() so it does not block others.

### PeerHandler Handles one peer connection

Each peer will be sending exactly one JSON message per connection and then it disconnects, Handler reads that one line, parses it, and routes to right method.

```java
String message = in.readLine();
JSONObject request = new JSONObject(message);
String command = request.getString("command");

switch (command) {
    case "register":      handleRegister(request); break;
    case "search":        handleSearch(request, out); break;
    case "update_chunk":  handleUpdateChunk(request); break;
}
```

So there are this 3 functionalities basically

handleRegister

```java
FileInfo fileInfo = new FileInfo(fileSize, chunkSize);
for (int i = 0; i < totalChunks; i++) {
    fileInfo.addPeerForChunk(i, peerAddress);
}
fileRegistry.put(filename, fileInfo);
```


When peer registers file, tracker creates FilInfo and records peer as having every chunk, No response will be sent back.

handleSearch

```java
FileInfo fileInfo = fileRegistry.get(filename);
JSONObject response = new JSONObject();
response.put("found", fileInfo != null);
if (fileInfo != null) response.put("fileInfo", fileInfo.toJSONObject());
out.println(response.toString());
```


This returns full FileInfo as JSON , include the entire chunkToPeersMap. Searchin peer now knows exactly which peer to contact for each chunk.

handleUpdateChunk

```java
FileInfo fileInfo = fileRegistry.get(filename);
fileInfo.addPeerForChunk(chunkIndex, peerAddress);
```

After peer finishes downloading the chunk it calls this. Tracker adds peer to chunks likst and now other peers looking for that chunk can get it from both sources.


addPeerForChunk - Thread Safety detail

```java
public synchronized void addPeerForChunk(int chunkIndex, String peerAddress) {
    chunkToPeersMap.computeIfAbsent(chunkIndex, k -> Collections.synchronizedList(new ArrayList<>()))
                   .add(peerAddress);
}
```

The computeIfAbsent sas if the chunk 3 has not been in list yet create one and then add the peer to it, synchronized on method and synchronizedList around array list prevent two threads from corrupting list at same time.


## Peer.java

Dual-role: acts as both client (downloads files) and server (serves chunks)

Has its own shared files directory (shared_files_p<port>/) and downloads dir (shared_files_p<port>_downloads/)

CLI interface with 3 commands: register, search, exit

### Initialization

```java
public Peer(String ip, int port, String sharedDirPath) {
    this.sharedDir = Paths.get(sharedDirPath);           // e.g., shared_files_p6001/
    this.downloadsDir = Paths.get(sharedDirPath + "_downloads");  // shared_files_p6001_downloads/
    Files.createDirectories(this.sharedDir);
    Files.createDirectories(this.downloadsDir);
}
```

Each peer has two folders: one for files it shares, one for files it downloads. Both are created automatically if they don't exist.

### start()

```java
public void start() {
    new Thread(new PeerServer(peerPort, sharedDir)).start();
    handleUserInput();
}
```

Two things happen simultaneously:

- A background thread starts PeerServer — this listens for other peers who want to download chunks from you

- The main thread enters handleUserInput() — this is your CLI


### registerFile()

```java
long fileSize = Files.size(filePath);
int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);

JSONObject request = new JSONObject();
request.put("command", "register");
request.put("peer", peerAddress);      // "127.0.0.1:6001"
request.put("filename", filename);
request.put("fileSize", fileSize);
request.put("chunkSize", CHUNK_SIZE);  // 262144
request.put("totalChunks", totalChunks);

out.println(request.toString());
```

Math.ceil((double) fileSize / CHUNK_SIZE) this is how we handle files that aren't perfectly divisible by 256 KB. A 700 KB file has ceil(700/256) = 3 chunks (two full, one partial).

### searchFile()

```java
// Send search request
JSONObject request = new JSONObject();
request.put("command", "search");
request.put("filename", filename);
out.println(request.toString());

// Read response
String responseStr = in.readLine();
JSONObject response = new JSONObject(responseStr);
if (response.getBoolean("found")) {
    JSONObject fileInfoJson = response.getJSONObject("fileInfo");
    new DownloadManager(filename, fileInfoJson).startDownload();
}
```


The search method just fetches metadata and hands it off to DownloadManager.

### DownloadManager

```java
JSONObject chunks = fileInfoJson.getJSONObject("chunks");
for (String key : chunks.keySet()) {
    int chunkIndex = Integer.parseInt(key);
    JSONArray peersArray = chunks.getJSONArray(key);
    List<String> peers = new ArrayList<>();
    for (int i = 0; i < peersArray.length(); i++) {
        peers.add(peersArray.getString(i));
    }
    this.chunkPeers.put(chunkIndex, peers);
}
```


The tracker sends back the chunk map as JSON. This code parses it back into a Map<Integer, List<String>> —> chunk index → list of peer addresses that have it.

Also note: BitSet downloadedChunks = new BitSet(totalChunks) —> this is a compact boolean array. Bit 3 being set means chunk 3 is done. Efficient for tracking many chunks.

### startDownload()

```java
try (RandomAccessFile raf = new RandomAccessFile(tempFilePath.toFile(), "rw")) {
    raf.setLength(fileSize);  // PRE-ALLOCATE the full file size on disk

    for (int i = 0; i < totalChunks; i++) {
        final int chunkIndex = i;
        Callable<Void> downloadTask = () -> {
            downloadChunk(chunkIndex, raf);
            return null;
        };
        futures.add(downloadExecutor.submit(downloadTask));
    }

    for (Future<?> future : futures) {
        future.get();  // WAIT for all chunks to finish
    }
}  // raf.close() happens here automatically

Files.move(tempFilePath, finalPath, REPLACE_EXISTING);  // rename .tmp → final
```

- Pre-allocation (raf.setLength(fileSize)): The file is created at full size immediately. This means chunk 5 can be written at its correct position even before chunks 0–4 are done —> enabling true parallelism.

- .tmp file: Downloads go to lorem.txt.tmp first. Only renamed to lorem.txt after all chunks succeed. Prevents partially-downloaded files from appearing complete.

- future.get(): Blocks until that chunk's download thread finishes. Collects all 10 (or however many) threads before proceeding to rename.

- raf.close() before Files.move(): The try-with-resources closes the file before the rename. On some OS (especially Windows), you can't move a file that's still open. This ordering is intentional.

### downloadChunk()

```java
String peerAddress = peers.get(0);  // Always picks the first available peer
String[] parts = peerAddress.split(":");
String host = parts[0];
int port = Integer.parseInt(parts[1]);

try (Socket socket = new Socket(host, port);
     PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
     DataInputStream in = new DataInputStream(socket.getInputStream())) {

    out.println(filename + ":" + chunkIndex);  // Request format

    int bytesToRead = (chunkIndex == totalChunks - 1)
        ? (int)(fileSize % chunkSize)   // Last chunk may be smaller
        : chunkSize;
    if (bytesToRead == 0 && fileSize > 0) bytesToRead = chunkSize;  // Edge case: perfectly divisible

    byte[] buffer = new byte[bytesToRead];
    in.readFully(buffer);  // Block until ALL bytes arrive

    synchronized (raf) {
        raf.seek((long) chunkIndex * chunkSize);  // Jump to correct position
        raf.write(buffer);
    }

    downloadedChunks.set(chunkIndex);
    informTrackerOfNewChunk(filename, chunkIndex);  // Tell tracker: "I now have this chunk"
}
```

- Last chunk math: If a 700 KB file has chunks of 256 KB, the last chunk is 700 % 256 = 188 KB. The code must request exactly 188 bytes, not 256, or readFully() will hang waiting for bytes that will never come.

- synchronized(raf): Multiple download threads write to the same RandomAccessFile. The synchronized block ensures only one thread writes at a time. Since each chunk writes to a different position (raf.seek), they don't actually overlap — but you still need the lock because seek + write must be atomic.

- DataInputStream.readFully(): Unlike a regular read() which may return fewer bytes, readFully blocks until the entire requested byte count arrives. Important for binary data over a network.


### informTrackerOfNewChunk()

```java
JSONObject request = new JSONObject();
request.put("command", "update_chunk");
request.put("peer", peerAddress);
request.put("filename", filename);
request.put("chunkIndex", chunkIndex);
out.println(request.toString());
```


Called after every successful chunk download. This is what makes the network self-distributing: as peers download chunks, they immediately become sources for those chunks for other peers.

## PeerServer.java

- Listens for incoming chunk requests from other peers

- Serves requested chunks using RandomAccessFile for byte-accurate reads

```java
while (true) {
    Socket clientSocket = serverSocket.accept();
    executor.submit(new ChunkDownloadHandler(clientSocket, sharedDir));
}
```


this Runs in a background daemon thread. Accepts incoming connections from peers who want to download chunks, spawns a handler thread for each.



### ChunkDownloadHandler.java

```java
String request = in.readLine();      // e.g., "lorem.txt:2"
String[] parts = request.split(":");
String filename = parts[0];
int chunkIndex = Integer.parseInt(parts[1]);

try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
    long fileSize = raf.length();
    long startOffset = (long) chunkIndex * CHUNK_SIZE;       // e.g., chunk 2 → byte 524288
    int bytesToRead = (int) Math.min(CHUNK_SIZE, fileSize - startOffset);  // handles last chunk

    byte[] buffer = new byte[bytesToRead];
    raf.seek(startOffset);
    raf.readFully(buffer);

    out.write(buffer);
    out.flush();
}
```

The server side mirrors the client side:

- Receives "filename:chunkIndex" as a plain text request
Calculates startOffset = chunkIndex × 256KB to know where in the file to start reading
- Math.min(CHUNK_SIZE, fileSize - startOffset) correctly handles the last chunk without over-reading
- Uses RandomAccessFile to jump directly to the right byte position (no sequential scan)
- Writes raw bytes to the socket output stream, closes, done

## How to build and run this

Clone the project

compile first using :

```bash
javac -cp lib/json-20231013.jar Peer.java Tracker.java
```


Run the tracker :

```bash
java -cp lib/json-20231013.jar Tracker
```


Run peers now :

```bash
java -cp lib/json-20231013.jar Peer 6001   # Peer 1
java -cp lib/json-20231013.jar Peer 6002   # Peer 2
```


CLI commands for each peer (just example) :

```
> register hello.txt     # Share a file from your shared_files_p<port>/ dir
> search hello.txt       # Find and download a file from the network
> exit                   # Shutdown peer
```


Dependencies required :

Java 8+

org.json - for JSON serialization over the network


Yes This is long, but I hope you understood, Thank you.
