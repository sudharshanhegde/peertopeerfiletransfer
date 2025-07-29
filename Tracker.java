import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Tracker {

    private static final int TRACKER_PORT = 8000;

    // NEW: The data structure is now more sophisticated.
    // The key is the filename.
    // The value is a FileInfo object containing all metadata about the file.
    private static final ConcurrentHashMap<String, FileInfo> fileRegistry = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        ExecutorService executor = Executors.newCachedThreadPool();
        try (ServerSocket serverSocket = new ServerSocket(TRACKER_PORT)) {
            System.out.println("Chunk-Aware Tracker server started on port " + TRACKER_PORT);
            while (true) {
                Socket peerSocket = serverSocket.accept();
                executor.submit(new PeerHandler(peerSocket));
            }
        } catch (IOException e) {
            System.err.println("Error starting the tracker server: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * A static nested class to hold all metadata for a registered file.
     * This makes the code much cleaner and more extensible.
     */
    static class FileInfo {
        long fileSize;
        int chunkSize;
        // Maps a chunk index to a list of peers that have that chunk.
        Map<Integer, List<String>> chunkToPeersMap;

        public FileInfo(long fileSize, int chunkSize) {
            this.fileSize = fileSize;
            this.chunkSize = chunkSize;
            this.chunkToPeersMap = new ConcurrentHashMap<>();
        }

        // Synchronized method to add a peer to a specific chunk's list.
        public synchronized void addPeerForChunk(int chunkIndex, String peerAddress) {
            chunkToPeersMap.computeIfAbsent(chunkIndex, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(peerAddress);
        }

        // Synchronized method to remove a peer from all chunk lists.
        public synchronized void removePeer(String peerAddress) {
            chunkToPeersMap.values().forEach(peerList -> peerList.remove(peerAddress));
        }

        // Convert the FileInfo object to a JSONObject for sending over the network.
        public JSONObject toJSONObject() {
            JSONObject json = new JSONObject();
            json.put("fileSize", this.fileSize);
            json.put("chunkSize", this.chunkSize);
            json.put("chunks", new JSONObject(this.chunkToPeersMap));
            return json;
        }
    }

    private static class PeerHandler implements Runnable {
        private final Socket peerSocket;
        private String peerAddress;

        public PeerHandler(Socket socket) {
            this.peerSocket = socket;
        }

        @Override
        public void run() {
            try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(peerSocket.getInputStream()));
                    PrintWriter out = new PrintWriter(peerSocket.getOutputStream(), true)) {
                String message;
                if ((message = in.readLine()) != null) {
                    JSONObject request = new JSONObject(message);
                    String command = request.getString("command");
                    this.peerAddress = request.optString("peer"); // Get peer address for logging

                    System.out.println("Received command '" + command + "' from peer " + peerAddress);

                    switch (command) {
                        case "register":
                            handleRegister(request);
                            break;
                        case "search":
                            handleSearch(request, out);
                            break;
                        case "update_chunk": // NEW command
                            handleUpdateChunk(request);
                            break;
                    }
                }
            } catch (Exception e) {
                System.err.println("Error handling peer " + peerAddress + ": " + e.getMessage());
            } finally {
                // In a real system with heartbeating, we would handle cleanup here.
                // For now, we still leave it commented out.
                // handleExit();
            }
        }

        /**
         * Handles registration of a new file with chunks.
         */
        private void handleRegister(JSONObject request) {
            String filename = request.getString("filename");
            long fileSize = request.getLong("fileSize");
            int chunkSize = request.getInt("chunkSize");
            int totalChunks = request.getInt("totalChunks");

            FileInfo fileInfo = new FileInfo(fileSize, chunkSize);
            // The registering peer has all chunks initially.
            for (int i = 0; i < totalChunks; i++) {
                fileInfo.addPeerForChunk(i, peerAddress);
            }
            fileRegistry.put(filename, fileInfo);
            System.out.println(
                    "Registered file '" + filename + "' from " + peerAddress + " with " + totalChunks + " chunks.");
        }

        /**
         * Handles a search request. Returns all metadata about the file.
         */
        private void handleSearch(JSONObject request, PrintWriter out) {
            String filename = request.getString("filename");
            FileInfo fileInfo = fileRegistry.get(filename);

            JSONObject response = new JSONObject();
            response.put("filename", filename);
            if (fileInfo != null) {
                response.put("found", true);
                response.put("fileInfo", fileInfo.toJSONObject());
            } else {
                response.put("found", false);
            }
            out.println(response.toString());
            System.out.println("Sent search results for '" + filename + "' to " + peerAddress);
        }

        /**
         * Handles a peer reporting that it has successfully downloaded a new chunk.
         */
        private void handleUpdateChunk(JSONObject request) {
            String filename = request.getString("filename");
            int chunkIndex = request.getInt("chunkIndex");

            FileInfo fileInfo = fileRegistry.get(filename);
            if (fileInfo != null) {
                fileInfo.addPeerForChunk(chunkIndex, peerAddress);
                System.out.println("Updated chunk map for '" + filename + "'. Peer " + peerAddress + " now has chunk "
                        + chunkIndex);
            }
        }

        // This method would be used with heartbeating to clean up disconnected peers.
        private void handleExit() {
            if (peerAddress == null)
                return;
            System.out.println("Peer " + peerAddress + " disconnected. Removing from registry.");
            fileRegistry.values().forEach(fileInfo -> fileInfo.removePeer(peerAddress));
        }
    }
}
