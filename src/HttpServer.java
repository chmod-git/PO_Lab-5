import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpServer {
    private static final int PORT = 8080;
    private static final String ROOT = "static";
    private static final int POOL_SIZE = 50;
    private static final ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE);
    private static Selector selector;

    public static void main(String[] args) throws IOException {
        selector = Selector.open();
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(PORT));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("HTTP server started on port " + PORT);

        while (true) {
            selector.select();
            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> it = keys.iterator();

            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                if (key.isAcceptable()) {
                    SocketChannel client = ((ServerSocketChannel) key.channel()).accept();
                    client.configureBlocking(false);
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    client.register(selector, SelectionKey.OP_READ, buffer);
                } else if (key.isReadable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    ByteBuffer buffer = (ByteBuffer) key.attachment();

                    int bytesRead = client.read(buffer);
                    if (bytesRead == -1) {
                        client.close();
                        continue;
                    }

                    buffer.flip();
                    String request = new String(buffer.array(), 0, buffer.limit());

                    if (request.contains("\r\n\r\n")) {
                        executor.submit(() -> {
                            try {
                                processRequest(key, request);
                                selector.wakeup();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                } else if (key.isWritable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    ByteBuffer responseBuffer = (ByteBuffer) key.attachment();

                    client.write(responseBuffer);
                    if (!responseBuffer.hasRemaining()) {
                        client.close();
                    }
                }
            }
        }
    }

    private static void processRequest(SelectionKey key, String request) throws IOException {
        String path = "/";
        String[] lines = request.split("\r\n");
        if (lines.length > 0 && lines[0].startsWith("GET")) {
            path = lines[0].split(" ")[1];
        }
        if (path.equals("/")) {
            path = "/index.html";
        }

        String fullPath = ROOT + path;
        byte[] content;
        String statusLine;
        String contentType;

        if (Files.exists(Paths.get(fullPath))) {
            content = Files.readAllBytes(Paths.get(fullPath));
            statusLine = "HTTP/1.1 200 OK\r\n";
            contentType = getMimeType(path);
        } else {
            content = "<h1>404 Not Found</h1>".getBytes();
            statusLine = "HTTP/1.1 404 Not Found\r\n";
            contentType = "text/html";
        }

        String headers = statusLine +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "Connection: close\r\n\r\n";

        ByteBuffer responseBuffer = ByteBuffer.allocate(headers.getBytes().length + content.length);
        responseBuffer.put(headers.getBytes());
        responseBuffer.put(content);
        responseBuffer.flip();

        key.attach(responseBuffer);
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private static String getMimeType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        return "application/octet-stream";
    }
}

//locust -f load_testing.py --host=http://localhost:8080