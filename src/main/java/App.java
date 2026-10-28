import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class App {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static String dbUrl;
    private static String dbUser;
    private static String dbPassword;

    public static void main(String[] args) throws Exception {
        dbUrl = env("DB_URL", "jdbc:postgresql://localhost:5432/app");
        dbUser = env("DB_USER", "postgres");
        dbPassword = env("DB_PASSWORD", "1234");

        initializeDatabase();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);
        server.createContext("/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("Method not allowed"));
                return;
            }
            ObjectNode result = JSON.createObjectNode();
            result.put("status", "OK");
            result.put("service", "tasks-api");
            sendJson(exchange, 200, result);
        });
        server.createContext("/tasks", App::handleTasks);
        server.start();

        System.out.println("Tasks API started on http://localhost:8080");
        System.out.println("Endpoints: GET /health, GET/POST /tasks, PUT/DELETE /tasks/{id}");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    }

    private static void initializeDatabase() throws Exception {
        int attempts = 30;
        while (true) {
            try (Connection connection = connect();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id BIGSERIAL PRIMARY KEY,
                        title VARCHAR(200) NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        done BOOLEAN NOT NULL DEFAULT FALSE
                    )
                    """);
                System.out.println("Connected to PostgreSQL; table tasks is ready.");
                return;
            } catch (SQLException e) {
                attempts--;
                if (attempts <= 0) throw e;
                System.out.println("Waiting for PostgreSQL... " + e.getMessage());
                Thread.sleep(2000);
            }
        }
    }

    private static void handleTasks(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("/tasks".equals(path) || "/tasks/".equals(path)) {
                if ("GET".equals(method)) {
                    sendJson(exchange, 200, getAllTasks());
                } else if ("POST".equals(method)) {
                    JsonNode body = readBody(exchange);
                    String title = body.path("title").asText("").trim();
                    String description = body.path("description").asText("");
                    boolean done = body.path("done").asBoolean(false);
                    if (title.isEmpty()) {
                        sendJson(exchange, 400, error("Field 'title' is required"));
                        return;
                    }
                    sendJson(exchange, 201, createTask(title, description, done));
                } else {
                    sendJson(exchange, 405, error("Method not allowed"));
                }
                return;
            }

            String suffix = path.substring("/tasks/".length());
            long id;
            try {
                id = Long.parseLong(suffix);
            } catch (NumberFormatException e) {
                sendJson(exchange, 404, error("Task not found"));
                return;
            }

            if ("PUT".equals(method)) {
                JsonNode body = readBody(exchange);
                String title = body.path("title").asText("").trim();
                String description = body.path("description").asText("");
                boolean done = body.path("done").asBoolean(false);
                if (title.isEmpty()) {
                    sendJson(exchange, 400, error("Field 'title' is required"));
                    return;
                }
                ObjectNode updated = updateTask(id, title, description, done);
                if (updated == null) sendJson(exchange, 404, error("Task not found"));
                else sendJson(exchange, 200, updated);
            } else if ("DELETE".equals(method)) {
                if (deleteTask(id)) sendJson(exchange, 200, message("Task deleted"));
                else sendJson(exchange, 404, error("Task not found"));
            } else {
                sendJson(exchange, 405, error("Method not allowed"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, error("Internal server error"));
        }
    }

    private static JsonNode readBody(HttpExchange exchange) throws IOException {
        return JSON.readTree(exchange.getRequestBody());
    }

    private static List<ObjectNode> getAllTasks() throws SQLException {
        List<ObjectNode> tasks = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, title, description, done FROM tasks ORDER BY id");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) tasks.add(taskFromRow(result));
        }
        return tasks;
    }

    private static ObjectNode createTask(String title, String description, boolean done) throws SQLException {
        String sql = "INSERT INTO tasks(title, description, done) VALUES (?, ?, ?) RETURNING id, title, description, done";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, description);
            statement.setBoolean(3, done);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return taskFromRow(result);
            }
        }
    }

    private static ObjectNode updateTask(long id, String title, String description, boolean done) throws SQLException {
        String sql = "UPDATE tasks SET title=?, description=?, done=? WHERE id=? RETURNING id, title, description, done";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, description);
            statement.setBoolean(3, done);
            statement.setLong(4, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? taskFromRow(result) : null;
            }
        }
    }

    private static boolean deleteTask(long id) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM tasks WHERE id=?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    private static ObjectNode taskFromRow(ResultSet result) throws SQLException {
        ObjectNode task = JSON.createObjectNode();
        task.put("id", result.getLong("id"));
        task.put("title", result.getString("title"));
        task.put("description", result.getString("description"));
        task.put("done", result.getBoolean("done"));
        return task;
    }

    private static ObjectNode error(String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("error", text);
        return node;
    }

    private static ObjectNode message(String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("message", text);
        return node;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
