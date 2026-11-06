package no.metatrack.server.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpGoCompatibilityIntegrationTest {
    private static final String HOOK_ACTION_NAME = "metatrack-batch-upload-admission";
    private static final String HOOK_RULE_NAME = "metatrack-batch-upload-admission";
    private static final String HOOK_COMPLETION_RULE_NAME = "metatrack-batch-upload-completion";
    private static final String LEGACY_ACTION_NAME = "metatrack-compat-hook";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    @Test
    @EnabledIfSystemProperty(named = "metatrack.sftpgo.compatibility.disposable", matches = "true")
    void verifiesDisposableSftpGoCompatibility(@TempDir Path tempDir) throws Exception {
        var settings = Settings.fromEnvironment();
        try (var hook = new SftpGoCompatibilityHook(settings.hookSecret(), "0.0.0.0", settings.hookPort())) {
            hook.start();
            var runner = new CompatibilityRunner(settings, hook);
            runner.run(tempDir);
        }
    }

    private static final class CompatibilityRunner {
        private final Settings settings;
        private final SftpGoCompatibilityHook hook;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        private CompatibilityRunner(Settings settings, SftpGoCompatibilityHook hook) {
            this.settings = settings;
            this.hook = hook;
        }

        private void run(Path tempDir) throws Exception {
            String token = getAdminToken();
            List<String> createdUsers = new ArrayList<>();
            Throwable failure = null;
            try {
                configureHooks(token);
                String runId = "sftpgo-compat-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                Path privateKey = tempDir.resolve("id_ed25519");
                Path sourcePath = tempDir.resolve("sample.fastq");
                generateKeyPair(privateKey);
                String publicKey = command(List.of("ssh-keygen", "-y", "-f", privateKey.toString()),
                        Duration.ofSeconds(15)).output().strip();
                Files.write(sourcePath, randomBytes(512 * 1024));

                String sameBatch = runId + "-one-batch";
                createUser(token, sameBatch, publicKey, 3, 3 * 512 * 1024L, 3, false, createdUsers);
                runAdmissionCase(token, List.of(sameBatch, sameBatch, sameBatch),
                        SftpGoCompatibilityHook.MAX_TRANSFERS_PER_BATCH, 1, privateKey, sourcePath, runId + "-same");

                List<String> globalBatches = new ArrayList<>();
                for (int index = 0; index < SftpGoCompatibilityHook.MAX_TRANSFERS_GLOBAL + 1; index++) {
                    String username = runId + "-batch-" + index;
                    createUser(token, username, publicKey, 1, 512 * 1024L, 1, false, createdUsers);
                    globalBatches.add(username);
                }
                runAdmissionCase(token, globalBatches, SftpGoCompatibilityHook.MAX_TRANSFERS_GLOBAL,
                        SftpGoCompatibilityHook.MAX_TRANSFERS_GLOBAL, privateKey, sourcePath, runId + "-global");
                runResumeCase(token, tempDir, runId, publicKey, privateKey, createdUsers);
                System.out.println("SFTPGo OSS admission compatibility passed: 2 per batch and 8 global transfers");
            } catch (Exception | Error exception) {
                failure = exception;
                throw exception;
            } finally {
                try {
                    deleteUsers(token, createdUsers);
                } catch (Exception cleanupException) {
                    if (failure == null) {
                        throw cleanupException;
                    }
                    failure.addSuppressed(cleanupException);
                }
            }
        }

        private String getAdminToken() throws Exception {
            String credentials = settings.adminUsername() + ":" + settings.adminPassword();
            String authorization = "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
            JsonNode response = send("GET", "/api/v2/token", authorization, null).body();
            String token = response.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IOException("SFTPGo token response did not contain an access token");
            }
            return token;
        }

        private void configureHooks(String token) throws Exception {
            String bearer = "Bearer " + token;
            JsonNode rulesResponse = apiCall("GET", "/api/v2/eventrules?offset=0&limit=500", bearer, null);
            for (JsonNode rule : array(rulesResponse, "event rules")) {
                boolean remove = Set.of(LEGACY_ACTION_NAME, HOOK_RULE_NAME, HOOK_COMPLETION_RULE_NAME)
                        .contains(rule.path("name").asText());
                for (JsonNode action : rule.path("actions")) {
                    remove |= action.path("name").asText().equals(LEGACY_ACTION_NAME);
                }
                if (remove) {
                    apiCall("DELETE", "/api/v2/eventrules/" + encodePath(rule.path("name").asText()), bearer, null);
                }
            }

            JsonNode actionResponse = apiCall("GET", "/api/v2/eventactions?offset=0&limit=500", bearer, null);
            for (JsonNode action : array(actionResponse, "event actions")) {
                if (action.path("name").asText().equals(LEGACY_ACTION_NAME)) {
                    apiCall("DELETE", "/api/v2/eventactions/" + encodePath(LEGACY_ACTION_NAME), bearer, null);
                }
            }
            boolean actionExists = StreamSupport.stream(array(actionResponse, "event actions").spliterator(), false)
                    .anyMatch(action -> action.path("name").asText().equals(HOOK_ACTION_NAME));
            ObjectNode action = objectMapper.createObjectNode();
            action.put("name", HOOK_ACTION_NAME);
            action.put("description", "Metatrack batch upload admission hook");
            action.put("type", 1);
            ObjectNode httpConfig = action.putObject("options").putObject("http_config");
            httpConfig.put("endpoint", hook.eventUrl(settings.dockerReachableHookHost()));
            httpConfig.put("username", SftpGoCompatibilityHook.USERNAME);
            httpConfig.putObject("password").put("status", "Plain").put("payload", settings.hookSecret());
            httpConfig.put("method", "POST");
            httpConfig.put("timeout", 10);
            httpConfig.put("body", "{\"event\":\"{{.Event}}\",\"username\":\"{{.Name}}\","
                    + "\"path\":\"{{.EscapedVirtualPath}}\",\"status\":{{.Status}},"
                    + "\"sizeBytes\":{{.FileSize}}}");
            httpConfig.putArray("headers").addObject().put("key", "Content-Type").put("value", "application/json");
            apiCall(actionExists ? "PUT" : "POST",
                    actionExists ? "/api/v2/eventactions/" + encodePath(HOOK_ACTION_NAME) : "/api/v2/eventactions",
                    bearer, action);

            createEventRule(bearer, HOOK_RULE_NAME, "pre-upload", true);
            createEventRule(bearer, HOOK_COMPLETION_RULE_NAME, "upload", false);

            JsonNode storedActions = apiCall("GET", "/api/v2/eventactions?offset=0&limit=500", bearer, null);
            JsonNode storedAction = StreamSupport.stream(array(storedActions, "event actions").spliterator(), false)
                    .filter(item -> item.path("name").asText().equals(HOOK_ACTION_NAME))
                    .findFirst().orElseThrow(() -> new IOException("SFTPGo did not retain the upload hook action"));
            String secretStatus = storedAction.path("options").path("http_config").path("password")
                    .path("status").asText();
            assertTrue(!secretStatus.isBlank() && !secretStatus.equals("Plain"),
                    "SFTPGo did not encrypt the batch hook secret at rest");
        }

        private void createEventRule(String bearer, String name, String event, boolean executeSync) throws Exception {
            ObjectNode rule = objectMapper.createObjectNode();
            rule.put("name", name);
            rule.put("description", "SFTPGo batch upload event handling");
            rule.put("status", 1);
            rule.put("trigger", 1);
            rule.putObject("conditions").putArray("fs_events").add(event);
            ObjectNode relation = rule.putArray("actions").addObject();
            relation.put("name", HOOK_ACTION_NAME);
            relation.put("order", 1);
            relation.putObject("relation_options").put("execute_sync", executeSync).put("stop_on_failure", true);
            apiCall("POST", "/api/v2/eventrules", bearer, rule);
        }

        private void createUser(String token, String username, String publicKey, int maxSessions,
                                long quotaSize, int quotaFiles, boolean allowDownload,
                                List<String> createdUsers) throws Exception {
            createdUsers.add(username);
            ObjectNode user = objectMapper.createObjectNode();
            user.put("username", username);
            user.put("status", 1);
            user.put("home_dir", "/srv/sftpgo/data/" + username);
            user.put("max_sessions", maxSessions);
            user.put("expiration_date", System.currentTimeMillis() + Duration.ofHours(1).toMillis());
            user.put("quota_size", quotaSize);
            user.put("quota_files", quotaFiles);
            user.putArray("public_keys").add(publicKey);
            ArrayNode permissions = user.putObject("permissions").putArray("/");
            permissions.add("list").add("upload").add("overwrite").add("create_dirs");
            if (allowDownload) {
                permissions.add("download");
            }
            user.putObject("filters").putArray("allowed_protocols").add("SFTP");
            ObjectNode s3 = user.putObject("filesystem").put("provider", 1).putObject("s3config");
            s3.put("bucket", settings.s3Bucket());
            s3.put("region", settings.s3Region());
            s3.put("endpoint", settings.s3Endpoint());
            s3.put("access_key", settings.s3AccessKey());
            s3.putObject("access_secret").put("status", "Plain").put("payload", settings.s3SecretKey());
            s3.put("key_prefix", "staging/" + username + "/");
            s3.put("force_path_style", true);
            apiCall("POST", "/api/v2/users", "Bearer " + token, user);
        }

        private void runAdmissionCase(String token, List<String> usernames, int admitted, int distinctUsers,
                                      Path privateKey, Path sourcePath, String runId) throws Exception {
            List<Process> processes = new ArrayList<>();
            List<String> remotePaths = new ArrayList<>();
            try {
                for (int index = 0; index < usernames.size(); index++) {
                    String remotePath = runId + "-" + index + ".fastq";
                    remotePaths.add(remotePath);
                    processes.add(startUpload(usernames.get(index), privateKey, sourcePath, remotePath, 256));
                }
                waitForAdmissions(admitted, distinctUsers, Duration.ofSeconds(10));
                waitForActiveTransfers(token, new HashSet<>(usernames), admitted, Set.copyOf(remotePaths),
                        Duration.ofSeconds(10));
                long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
                List<Integer> exitCodes = new ArrayList<>();
                for (Process process : processes) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                        throw new IOException("SFTP upload process did not finish within 40 seconds");
                    }
                    exitCodes.add(process.exitValue());
                }
                long successful = exitCodes.stream().filter(code -> code == 0).count();
                assertEquals(admitted, successful, "Unexpected SFTP upload exit codes: " + exitCodes);
                waitForHookIdle(Duration.ofSeconds(10));
            } finally {
                processes.forEach(SftpGoCompatibilityIntegrationTest::terminate);
            }
        }

        private void runResumeCase(String token, Path tempDir, String runId, String publicKey, Path privateKey,
                                   List<String> createdUsers) throws Exception {
            String username = runId + "-resume";
            Path sourcePath = tempDir.resolve("resume-source.fastq");
            Path downloadedPath = tempDir.resolve("resume-downloaded.fastq");
            String remotePath = runId + "-resume.fastq";
            byte[] source = randomBytes(16 * 1024 * 1024);
            Files.write(sourcePath, source);
            createUser(token, username, publicKey, 1, source.length, 1, true, createdUsers);
            Process upload = startUpload(username, privateKey, sourcePath, remotePath, 4096);
            try {
                Thread.sleep(Duration.ofSeconds(12));
                assertTrue(upload.isAlive(), "Interrupted-upload fixture completed before it could be stopped");
                terminate(upload);
                waitForNoUserConnections(token, username, Duration.ofSeconds(15));

                CommandResult listing = sftpCommand(username, privateKey, "ls -l " + sftpQuote(remotePath),
                        Duration.ofSeconds(20));
                Long partialSize = partialSize(listing.output(), remotePath);
                assertEquals(0, listing.exitCode(), "Could not inspect SFTPGo partial object: " + listing.output());
                assertNotNull(partialSize, "SFTPGo did not expose a partial object: " + listing.output());
                assertTrue(partialSize > 0 && partialSize < source.length,
                        "SFTPGo partial size must be between zero and the complete object size");

                releaseTestReservation(username, remotePath);
                CommandResult resumed = sftpCommand(username, privateKey,
                        "reput " + sftpQuote(sourcePath.toString()) + " " + sftpQuote(remotePath),
                        Duration.ofSeconds(90));
                assertEquals(0, resumed.exitCode(), "SFTPGo OSS resume failed: " + resumed.output());
                CommandResult downloaded = sftpCommand(username, privateKey,
                        "get " + sftpQuote(remotePath) + " " + sftpQuote(downloadedPath.toString()),
                        Duration.ofSeconds(30));
                assertEquals(0, downloaded.exitCode(), "SFTPGo OSS could not read back the resumed S3 object");
                assertTrue(MessageDigest.isEqual(sha256(sourcePath), sha256(downloadedPath)),
                        "Resumed S3 object content differs from the original source");
                waitForHookIdle(Duration.ofSeconds(10));
            } finally {
                terminate(upload);
            }
        }

        private Process startUpload(String username, Path privateKey, Path sourcePath, String remotePath,
                                    int speedLimit) throws IOException {
            Process process = new ProcessBuilder(sftpArguments(username, privateKey, "-l", Integer.toString(speedLimit)))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (var input = process.getOutputStream()) {
                input.write(("put " + sftpQuote(sourcePath.toString()) + " " + sftpQuote(remotePath) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                terminate(process);
                throw exception;
            }
            return process;
        }

        private CommandResult sftpCommand(String username, Path privateKey, String command, Duration timeout)
                throws Exception {
            List<String> arguments = sftpArguments(username, privateKey);
            Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                }
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("SFTP command timed out: " + command);
                }
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return new CommandResult(process.exitValue(), output);
            } finally {
                terminate(process);
            }
        }

        private List<String> sftpArguments(String username, Path privateKey, String... extraArguments) {
            List<String> arguments = new ArrayList<>(List.of("sftp"));
            if (extraArguments.length > 0) {
                arguments.addAll(List.of(extraArguments));
            }
            arguments.addAll(List.of("-i", privateKey.toString(), "-P", Integer.toString(settings.sftpPort()),
                    "-o", "IdentitiesOnly=yes", "-o", "PubkeyAuthentication=yes", "-o",
                    "PreferredAuthentications=publickey", "-o", "PasswordAuthentication=no", "-o",
                    "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null", "-b", "-",
                    username + "@" + settings.sftpHost()));
            return arguments;
        }

        private void releaseTestReservation(String username, String path) throws Exception {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("username", username);
            body.put("path", path);
            String credentials = SftpGoCompatibilityHook.USERNAME + ":" + settings.hookSecret();
            String authorization = "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
            HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder(
                            URI.create(hook.localUrl("/test/release-reservation")))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode(), "Compatibility hook could not release interrupted reservation");
        }

        private void waitForActiveTransfers(String token, Set<String> usernames, int expectedCount,
                                            Set<String> expectedPaths, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            int lastCount = 0;
            JsonNode lastTransfer = null;
            Set<String> lastPaths = Set.of();
            while (System.nanoTime() < deadline) {
                JsonNode connections = apiCall("GET", "/api/v2/connections", "Bearer " + token, null);
                List<JsonNode> transfers = new ArrayList<>();
                for (JsonNode connection : array(connections, "SFTPGo connections")) {
                    if (!usernames.contains(connection.path("username").asText())) {
                        continue;
                    }
                    JsonNode active = connection.has("active_transfers")
                            ? connection.path("active_transfers") : connection.path("transfers");
                    if (active.isArray()) {
                        active.forEach(transfers::add);
                    }
                }
                lastCount = transfers.size();
                if (!transfers.isEmpty()) {
                    lastTransfer = transfers.getFirst();
                }
                Set<String> observedPaths = new HashSet<>();
                for (JsonNode transfer : transfers) {
                    String path = firstText(transfer, "path", "virtual_path", "file_path");
                    if (path != null) {
                        observedPaths.add(path.replaceFirst("^/+", "").replaceFirst("^.*/", ""));
                    }
                }
                lastPaths = observedPaths;
                if (lastCount >= expectedCount) {
                    assertTrue(hasAny(lastTransfer, "path", "virtual_path", "file_path"),
                            "SFTPGo active transfer omitted its path: " + lastTransfer);
                    assertTrue(hasAny(lastTransfer, "size", "file_size", "current_size"),
                            "SFTPGo active transfer omitted its byte count: " + lastTransfer);
                    if (observedPaths.stream().filter(expectedPaths::contains).count() >= expectedCount) {
                        return;
                    }
                }
                Thread.sleep(100);
            }
            throw new AssertionError("SFTPGo connections API exposed " + lastCount + " active transfers, expected "
                    + expectedCount + "; last transfer=" + lastTransfer + ", observed paths=" + lastPaths);
        }

        private void waitForNoUserConnections(String token, String username, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode connections = apiCall("GET", "/api/v2/connections", "Bearer " + token, null);
                boolean connected = StreamSupport.stream(
                                array(connections, "SFTPGo connections").spliterator(), false)
                        .anyMatch(connection -> connection.path("username").asText().equals(username));
                if (!connected) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("SFTPGo connection for " + username + " did not drain");
        }

        private void waitForHookIdle(Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (hookState().path("active_transfers").asInt(-1) == 0) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Asynchronous SFTPGo upload events did not release compatibility-hook slots");
        }

        private void waitForAdmissions(int count, int distinctUsers, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            JsonNode lastState = null;
            while (System.nanoTime() < deadline) {
                lastState = hookState();
                if (lastState.path("active_transfers").asInt(-1) == count
                        && lastState.path("users").size() == distinctUsers) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Expected " + count + " admissions for " + distinctUsers + " batches, got "
                    + lastState);
        }

        private JsonNode hookState() throws Exception {
            HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder(
                            URI.create(hook.localUrl("/healthz")))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("Compatibility hook health endpoint returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        }

        private void generateKeyPair(Path privateKey) throws Exception {
            command(List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", privateKey.toString()),
                    Duration.ofSeconds(15));
        }

        private CommandResult command(List<String> arguments, Duration timeout) throws Exception {
            Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Command timed out: " + arguments.getFirst());
                }
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (process.exitValue() != 0) {
                    throw new IOException("Command failed (" + process.exitValue() + "): " + arguments.getFirst()
                            + ": " + output);
                }
                return new CommandResult(process.exitValue(), output);
            } finally {
                terminate(process);
            }
        }

        private void deleteUsers(String token, List<String> createdUsers) throws Exception {
            Exception cleanupFailure = null;
            for (String username : createdUsers) {
                try {
                    ApiResponse response = send("DELETE", "/api/v2/users/" + encodePath(username),
                            "Bearer " + token, null);
                    if (response.statusCode() != 404 && (response.statusCode() < 200 || response.statusCode() >= 300)) {
                        throw new IOException("SFTPGo user cleanup returned HTTP " + response.statusCode());
                    }
                } catch (Exception exception) {
                    if (cleanupFailure == null) {
                        cleanupFailure = exception;
                    } else {
                        cleanupFailure.addSuppressed(exception);
                    }
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        private JsonNode apiCall(String method, String path, String authorization, JsonNode payload) throws Exception {
            ApiResponse response = send(method, path, authorization, payload);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("SFTPGo API " + method + " " + path + " returned HTTP " + response.statusCode());
            }
            return response.body();
        }

        private ApiResponse send(String method, String path, String authorization, JsonNode payload) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.apiUrl() + path))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", authorization);
            if (payload == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(payload)));
            }
            HttpResponse<byte[]> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new IOException("SFTPGo API returned an unexpectedly large response");
            }
            JsonNode json = body.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(body);
            return new ApiResponse(response.statusCode(), json);
        }

        private ArrayNode array(JsonNode node, String label) throws IOException {
            if (node instanceof ArrayNode array) {
                return array;
            }
            throw new IOException("SFTPGo response was not a JSON array: " + label);
        }

        private String encodePath(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }

        private String firstText(JsonNode node, String... fields) {
            for (String field : fields) {
                if (node.hasNonNull(field) && node.get(field).isTextual()) {
                    return node.get(field).asText();
                }
            }
            return null;
        }

        private boolean hasAny(JsonNode node, String... fields) {
            for (String field : fields) {
                if (node != null && node.has(field)) {
                    return true;
                }
            }
            return false;
        }

        private Long partialSize(String output, String remotePath) {
            for (String line : output.split("\\R")) {
                String trimmed = line.strip();
                String[] columns = trimmed.split("\\s+");
                if (trimmed.endsWith(remotePath) && columns.length >= 5 && columns[4].matches("\\d+")) {
                    return Long.parseLong(columns[4]);
                }
            }
            return null;
        }

        private byte[] sha256(Path path) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        }

        private byte[] randomBytes(int size) {
            byte[] bytes = new byte[size];
            new SecureRandom().nextBytes(bytes);
            return bytes;
        }

        private String sftpQuote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    private static void terminate(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }


    private static Long parsePort(String value, String variable) {
        try {
            long port = Long.parseLong(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(variable + " must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(variable + " must be an integer", exception);
        }
    }

    private record Settings(String apiUrl, String adminUsername, String adminPassword, String sftpHost,
                            int sftpPort, int hookPort, String dockerReachableHookHost, String hookSecret,
                            String s3Endpoint, String s3Bucket, String s3AccessKey, String s3SecretKey,
                            String s3Region) {
        private static Settings fromEnvironment() {
            Map<String, String> environment = System.getenv();
            String hookPort = environment.getOrDefault("SFTPGO_COMPATIBILITY_HOOK_PORT", "12345");
            String sftpPort = environment.getOrDefault("SFTPGO_SFTP_HOST_PORT",
                    environment.getOrDefault("SFTPGO_SFTP_PORT", "12022"));
            Settings settings = new Settings(
                    environment.getOrDefault("SFTPGO_API_URL", "http://localhost:18082").replaceAll("/+$", ""),
                    required(environment, "SFTPGO_ADMIN_USERNAME"),
                    required(environment, "SFTPGO_ADMIN_PASSWORD"),
                    environment.getOrDefault("SFTPGO_SFTP_HOST", "localhost"),
                    Math.toIntExact(parsePort(sftpPort, "SFTPGO_SFTP_HOST_PORT")),
                    Math.toIntExact(parsePort(hookPort, "SFTPGO_COMPATIBILITY_HOOK_PORT")),
                    environment.getOrDefault("SFTPGO_COMPATIBILITY_HOOK_DOCKER_HOST", "host.docker.internal"),
                    required(environment, "SFTPGO_HOOK_SECRET"),
                    environment.getOrDefault("SFTPGO_S3_ENDPOINT", "http://silo:9000"),
                    required(environment, "SFTPGO_S3_BUCKET_NAME"),
                    required(environment, "SFTPGO_S3_ACCESS_KEY"),
                    required(environment, "SFTPGO_S3_SECRET_KEY"),
                    required(environment, "SFTPGO_S3_REGION"));
            if (settings.hookSecret().length() < 32) {
                throw new IllegalArgumentException("SFTPGO_HOOK_SECRET must contain at least 32 characters");
            }
            URI apiUri = URI.create(settings.apiUrl());
            if (!Set.of("http", "https").contains(apiUri.getScheme()) || apiUri.getHost() == null) {
                throw new IllegalArgumentException("SFTPGO_API_URL must be an absolute HTTP(S) URL");
            }
            return settings;
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required environment variable " + name);
            }
            return value;
        }
    }

    private record ApiResponse(int statusCode, JsonNode body) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}