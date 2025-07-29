import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Peer client is the application that users run to participate in the
 * network.
 *
 * It has two main responsibilities:
 * 1. Act as a CLIENT: It connects to the Tracker to register files and search
 * for files.
 * 2. Act as a SERVER: It listens for incoming connections from other peers to
 * let them download files.
 */
public class Peer {

    // Connection info for the Tracker
    private static final String TRACKER_IP = "127.0.0.1";
    private static final int TRACKER_PORT = 8000;

    // Info for this peer's own server
    private final String peerIp;
    private final int peerPort;
    private final String peerAddress; // e.g., "127.0.0.1:6001"

    // The directory where this peer's shared files are located.
    private final Path sharedDir;
    // NEW: The directory where downloaded files will be saved.
    private final Path downloadsDir;

    public Peer(String ip, int port, String sharedDirPath) {
        this.peerIp = ip;
        this.peerPort = port;
        this.peerAddress = ip + ":" + port;
        this.sharedDir = Paths.get(sharedDirPath);
        // NEW: Define and create the downloads directory.
        this.downloadsDir = Paths.get(sharedDirPath + "_downloads");

        // Create the shared and downloads directories if they don't exist.
        try {
            if (!Files.exists(this.sharedDir))
                Files.createDirectories(this.sharedDir);
            if (!Files.exists(this.downloadsDir))
                Files.createDirectories(this.downloadsDir);
        } catch (IOException e) {
            System.err.println("Could not create directories: " + e.getMessage());
        }
    }

    /**
     * The main logic for the peer.
     */
    public void start() {
        new Thread(new PeerServer(peerPort, sharedDir)).start();
        registerWithTracker();

        System.out.println("\nPeer started on " + peerAddress);
        System.out.println("Sharing files from: " + sharedDir.toAbsolutePath());
        System.out.println("Downloading files to: " + downloadsDir.toAbsolutePath());
        System.out.println("Enter commands (e.g., 'search <filename>', 'exit'):");
        handleUserInput();
    }

    private List<String> getSharedFiles() {
        try (Stream<Path> stream = Files.list(sharedDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Error reading shared files: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private void registerWithTracker() {
        try (Socket socket = new Socket(TRACKER_IP, TRACKER_PORT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            List<String> files = getSharedFiles();
            if (files.isEmpty()) {
                System.out.println("No files to share. Registration skipped.");
                return;
            }

            JSONObject request = new JSONObject();
            request.put("command", "register");
            request.put("peer", peerAddress);
            request.put("files", new JSONArray(files));

            out.println(request.toString());
            System.out.println("Registered files with tracker: " + files);

        } catch (IOException e) {
            System.err.println("Could not connect to tracker: " + e.getMessage());
        }
    }

    private void handleUserInput() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine();
                String[] parts = line.split(" ", 2);
                String command = parts[0].toLowerCase();

                if ("search".equals(command) && parts.length > 1) {
                    searchFile(parts[1]);
                } else if ("exit".equals(command)) {
                    System.out.println("Exiting...");
                    System.exit(0);
                } else {
                    System.out.println("Unknown command. Available: 'search <filename>', 'exit'");
                }
            }
        }
    }

    private void searchFile(String filename) {
        try (Socket socket = new Socket(TRACKER_IP, TRACKER_PORT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            JSONObject request = new JSONObject();
            request.put("command", "search");
            request.put("filename", filename);
            out.println(request.toString());

            String responseStr = in.readLine();
            if (responseStr != null) {
                JSONObject response = new JSONObject(responseStr);
                JSONArray peers = response.getJSONArray("peers");
                if (peers.length() > 0) {
                    System.out.println("File '" + filename + "' found at peers: " + peers);
                    // NEW: Automatically try to download from the first available peer.
                    String peerToDownloadFrom = peers.getString(0);
                    downloadFile(peerToDownloadFrom, filename);
                } else {
                    System.out.println("File '" + filename + "' not found on the network.");
                }
            }
        } catch (IOException e) {
            System.err.println("Error searching for file: " + e.getMessage());
        }
    }

    /**
     * NEW: Connects to another peer and downloads a file.
     * 
     * @param peerAddress The address of the peer to download from (e.g.,
     *                    "127.0.0.1:6001").
     * @param filename    The name of the file to download.
     */
    private void downloadFile(String peerAddress, String filename) {
        System.out.println("Attempting to download '" + filename + "' from " + peerAddress + "...");
        String[] parts = peerAddress.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        try (Socket socket = new Socket(host, port);
                // Send the name of the file we want to download.
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                // Prepare to receive the file's binary data.
                BufferedInputStream fileIn = new BufferedInputStream(socket.getInputStream())) {

            // 1. Request the file
            out.println(filename);

            // 2. Receive the file and save it.
            Path outputPath = downloadsDir.resolve(filename);
            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fileIn.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            System.out.println("Successfully downloaded '" + filename + "' to " + outputPath.toAbsolutePath());

        } catch (IOException e) {
            System.err.println("Download failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java Peer <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        String sharedDirPath = "shared_files_p" + port;

        Peer peer = new Peer("127.0.0.1", port, sharedDirPath);
        peer.start();
    }
}

/**
 * The server component of the Peer. It runs in a separate thread.
 */
class PeerServer implements Runnable {
    private final int port;
    private final Path sharedDir;
    // NEW: Use a thread pool to handle multiple simultaneous downloads.
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
                System.out.println("Received a download request from peer: " + clientSocket.getInetAddress());
                // NEW: Hand off the download request to a new thread.
                executor.submit(new FileDownloadHandler(clientSocket, sharedDir));
            }
        } catch (IOException e) {
            System.err.println("Peer server error: " + e.getMessage());
        }
    }
}

/**
 * NEW: A dedicated handler for processing a single file download request.
 * This runs on a thread from the PeerServer's thread pool.
 */
class FileDownloadHandler implements Runnable {
    private final Socket clientSocket;
    private final Path sharedDir;

    public FileDownloadHandler(Socket socket, Path sharedDir) {
        this.clientSocket = socket;
        this.sharedDir = sharedDir;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                BufferedOutputStream fileOut = new BufferedOutputStream(clientSocket.getOutputStream())) {

            // 1. Read the requested filename from the client.
            String filename = in.readLine();
            if (filename == null)
                return;

            System.out.println("Peer requested file: " + filename);

            // 2. Find the file in our shared directory.
            Path filePath = sharedDir.resolve(filename).toAbsolutePath();
            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                // 3. Stream the file's bytes to the client.
                try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        fileOut.write(buffer, 0, bytesRead);
                    }
                    fileOut.flush();
                    System.out.println("Finished sending file: " + filename);
                }
            } else {
                System.err.println("Requested file '" + filename + "' not found or is a directory.");
            }
        } catch (IOException e) {
            System.err.println("Error handling download request: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
