import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Peer {

    private static final String TRACKER_IP = "127.0.0.1";
    private static final int TRACKER_PORT = 8000;
    private static final int CHUNK_SIZE = 1024 * 256; // 256 KB

    private final String peerIp;
    private final int peerPort;
    private final String peerAddress;
    private final Path sharedDir;
    private final Path downloadsDir;

    public Peer(String ip, int port, String sharedDirPath) {
        this.peerIp = ip;
        this.peerPort = port;
        this.peerAddress = ip + ":" + port;
        this.sharedDir = Paths.get(sharedDirPath);
        this.downloadsDir = Paths.get(sharedDirPath + "_downloads");

        try {
            if (!Files.exists(this.sharedDir))
                Files.createDirectories(this.sharedDir);
            if (!Files.exists(this.downloadsDir))
                Files.createDirectories(this.downloadsDir);
        } catch (IOException e) {
            System.err.println("Could not create directories: " + e.getMessage());
        }
    }

    public void start() {
        new Thread(new PeerServer(peerPort, sharedDir)).start();
        System.out.println("\nPeer started on " + peerAddress);
        System.out.println("Sharing files from: " + sharedDir.toAbsolutePath());
        System.out.println("Downloading files to: " + downloadsDir.toAbsolutePath());
        System.out.println("Enter commands: 'register <filename>', 'search <filename>', 'exit'");
        handleUserInput();
    }

    private void handleUserInput() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine();
                String[] parts = line.split(" ", 2);
                String command = parts[0].toLowerCase();

                if ("register".equals(command) && parts.length > 1) {
                    registerFile(parts[1]);
                } else if ("search".equals(command) && parts.length > 1) {
                    searchFile(parts[1]);
                } else if ("exit".equals(command)) {
                    System.out.println("Exiting...");
                    System.exit(0);
                } else {
                    System.out
                            .println("Unknown command. Available: 'register <filename>', 'search <filename>', 'exit'");
                }
            }
        }
    }

    private void registerFile(String filename) {
        try {
            Path filePath = sharedDir.resolve(filename);
            if (!Files.exists(filePath)) {
                System.err.println("File not found in shared directory: " + filename);
                return;
            }
            long fileSize = Files.size(filePath);
            int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);

            try (Socket socket = new Socket(TRACKER_IP, TRACKER_PORT);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                JSONObject request = new JSONObject();
                request.put("command", "register");
                request.put("peer", peerAddress);
                request.put("filename", filename);
                request.put("fileSize", fileSize);
                request.put("chunkSize", CHUNK_SIZE);
                request.put("totalChunks", totalChunks);

                out.println(request.toString());
                System.out.println("Registered '" + filename + "' with the tracker.");
            }
        } catch (IOException e) {
            System.err.println("Could not register file: " + e.getMessage());
        }
    }

    private void searchFile(String filename) {
        try (Socket socket = new Socket(TRACKER_IP, TRACKER_PORT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            JSONObject request = new JSONObject();
            request.put("command", "search");
            request.put("filename", filename);
            request.put("peer", peerAddress);
            out.println(request.toString());

            String responseStr = in.readLine();
            if (responseStr != null) {
                JSONObject response = new JSONObject(responseStr);
                if (response.getBoolean("found")) {
                    System.out.println("File found. Starting download...");
                    JSONObject fileInfoJson = response.getJSONObject("fileInfo");
                    new DownloadManager(filename, fileInfoJson).startDownload();
                } else {
                    System.out.println("File not found on the network.");
                }
            }
        } catch (IOException e) {
            System.err.println("Error searching for file: " + e.getMessage());
        }
    }

    private void informTrackerOfNewChunk(String filename, int chunkIndex) {
        try (Socket socket = new Socket(TRACKER_IP, TRACKER_PORT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            JSONObject request = new JSONObject();
            request.put("command", "update_chunk");
            request.put("peer", peerAddress);
            request.put("filename", filename);
            request.put("chunkIndex", chunkIndex);
            out.println(request.toString());
        } catch (IOException e) {
            System.err.println("Could not update tracker about new chunk: " + e.getMessage());
        }
    }

    class DownloadManager {
        private final String filename;
        private final long fileSize;
        private final int chunkSize;
        private final int totalChunks;
        private final Map<Integer, List<String>> chunkPeers;
        private final BitSet downloadedChunks;
        private final ExecutorService downloadExecutor;

        public DownloadManager(String filename, JSONObject fileInfoJson) {
            this.filename = filename;
            this.fileSize = fileInfoJson.getLong("fileSize");
            this.chunkSize = fileInfoJson.getInt("chunkSize");
            this.totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
            this.chunkPeers = new HashMap<>();
            this.downloadedChunks = new BitSet(totalChunks);
            this.downloadExecutor = Executors.newFixedThreadPool(10); // Download up to 10 chunks in parallel

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
        }

        public void startDownload() {
            System.out.println("Starting download for " + filename + " (" + totalChunks + " chunks)");
            Path tempFilePath = downloadsDir.resolve(filename + ".tmp");
            List<Future<?>> futures = new ArrayList<>();

            try {
                // The try-with-resources block now ONLY handles the RandomAccessFile
                try (RandomAccessFile raf = new RandomAccessFile(tempFilePath.toFile(), "rw")) {
                    raf.setLength(fileSize); // Pre-allocate file space

                    for (int i = 0; i < totalChunks; i++) {
                        final int chunkIndex = i;
                        Callable<Void> downloadTask = () -> {
                            downloadChunk(chunkIndex, raf);
                            return null;
                        };
                        futures.add(downloadExecutor.submit(downloadTask));
                    }

                    // Wait for all downloads to complete
                    for (Future<?> future : futures) {
                        future.get();
                    }
                } // <-- raf.close() is automatically called here, releasing the file lock.

                downloadExecutor.shutdown();
                System.out.println("All chunks downloaded. Reassembling file...");
                Path finalPath = downloadsDir.resolve(filename);
                // Now this move operation will succeed because the .tmp file is no longer
                // locked.
                Files.move(tempFilePath, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Download complete: " + finalPath);

            } catch (Exception e) {
                System.err.println("Download failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void downloadChunk(int chunkIndex, RandomAccessFile raf) {
            List<String> peers = chunkPeers.get(chunkIndex);
            if (peers == null || peers.isEmpty()) {
                System.err.println("No peers found for chunk " + chunkIndex);
                return;
            }

            String peerAddress = peers.get(0); // Simple strategy: pick the first peer
            String[] parts = peerAddress.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            try (Socket socket = new Socket(host, port);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    DataInputStream in = new DataInputStream(socket.getInputStream())) {

                out.println(filename + ":" + chunkIndex); // Request format: "filename:chunkIndex"

                int bytesToRead = (chunkIndex == totalChunks - 1) ? (int) (fileSize % chunkSize) : chunkSize;
                if (bytesToRead == 0 && fileSize > 0)
                    bytesToRead = chunkSize; // Last chunk is full size

                byte[] buffer = new byte[bytesToRead];
                in.readFully(buffer);

                synchronized (raf) {
                    raf.seek((long) chunkIndex * chunkSize);
                    raf.write(buffer);
                }

                downloadedChunks.set(chunkIndex);
                System.out.println("Successfully downloaded chunk " + chunkIndex + " of " + filename);
                informTrackerOfNewChunk(filename, chunkIndex);

            } catch (IOException e) {
                System.err.println("Failed to download chunk " + chunkIndex + ": " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java Peer <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        String sharedDirPath = "shared_files_p" + port;
        new Peer("127.0.0.1", port, sharedDirPath).start();
    }
}

class PeerServer implements Runnable {
    private final int port;
    private final Path sharedDir;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public PeerServer(int port, Path sharedDir) {
        this.port = port;
        this.sharedDir = sharedDir;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Peer's internal server is listening on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(new ChunkDownloadHandler(clientSocket, sharedDir));
            }
        } catch (IOException e) {
            System.err.println("Peer server error: " + e.getMessage());
        }
    }
}

class ChunkDownloadHandler implements Runnable {
    private static final int CHUNK_SIZE = 1024 * 256; // 256 KB
    private final Socket clientSocket;
    private final Path sharedDir;

    public ChunkDownloadHandler(Socket socket, Path sharedDir) {
        this.clientSocket = socket;
        this.sharedDir = sharedDir;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                BufferedOutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())) {

            String request = in.readLine();
            if (request == null)
                return;

            String[] parts = request.split(":");
            String filename = parts[0];
            int chunkIndex = Integer.parseInt(parts[1]);

            System.out.println("Peer requested chunk " + chunkIndex + " of '" + filename + "'");

            Path filePath = sharedDir.resolve(filename);
            if (Files.exists(filePath)) {
                try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
                    long fileSize = raf.length();
                    long startOffset = (long) chunkIndex * CHUNK_SIZE;
                    int bytesToRead = (int) Math.min(CHUNK_SIZE, fileSize - startOffset);

                    byte[] buffer = new byte[bytesToRead];
                    raf.seek(startOffset);
                    raf.readFully(buffer);

                    out.write(buffer);
                    out.flush();
                }
            }
        } catch (IOException e) {
            System.err.println("Error handling chunk download request: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                /* ignore */ }
        }
    }
}
