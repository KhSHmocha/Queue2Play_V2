import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;

/** File storage with a recoverable journal. No database or external library is required. */
final class AppStorage {
    private static final String[] FILES = {"accounts.txt", "games.txt", "rentals.txt", "queue.txt", "logs.txt"};
    final Path directory;
    private final Path journal;
    private final Map<String, byte[]> original = new LinkedHashMap<>();

    // Open the data folder and recover any interrupted save.
    AppStorage() {
        String configured = System.getProperty("q2p.dataDir", System.getenv("Q2P_DATA_DIR"));
        try {
            Path base = Paths.get(AppStorage.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(base)) {
                base = base.getParent();
            }
            Path dataDirectory;
            if (configured == null) {
                dataDirectory = base.resolve("data");
            } else {
                dataDirectory = Paths.get(configured);
            }
            directory = dataDirectory.toAbsolutePath().normalize();
            journal = directory.resolve(".transaction");
            Files.createDirectories(directory);
            try (FileChannel channel = lockChannel(); FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new IOException("Another QUEUE2PLAY process is using this data folder.");
                }
                recover();
                if (configured == null && !hasSavedFiles()) {
                    // Import old flat-file layouts without deleting the originals.
                    Path legacy = directory.getParent();
                    for (String name : FILES) {
                        if (Files.isRegularFile(legacy.resolve(name))) {
                            Files.copy(legacy.resolve(name), file(name));
                        }
                    }
                }
                rememberFiles();
            }
        } catch (Exception e) {
            throw new StorageException("Cannot open the data folder: " + e.getMessage(), e);
        }
    }

    // Check whether the data folder already contains saved records.
    private boolean hasSavedFiles() {
        for (String name : FILES) {
            if (Files.exists(file(name))) {
                return true;
            }
        }
        return false;
    }

    // Return the path of a named data file.
    Path file(String name) {
        return directory.resolve(name);
    }

    // Open the lock file used to prevent simultaneous saves.
    private FileChannel lockChannel() throws IOException {
        return FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    // Keep file bytes in a map to detect outside changes before saving.
    private void rememberFiles() throws IOException {
        original.clear();
        for (String name : FILES) {
            Path target = file(name);
            if (Files.exists(target) && !Files.isRegularFile(target)) {
                throw new IOException(name + " must be a regular file.");
            }
            byte[] bytes = null;
            if (Files.exists(target)) {
                bytes = Files.readAllBytes(target);
            }
            original.put(name, bytes);
        }
    }

    // Write all bytes and flush them before replacing a saved file.
    private static void durableWrite(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    // Replace a file, using an atomic move when supported.
    private static void replace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Delete only the known temporary transaction files.
    private void cleanJournal() throws IOException {
        if (!Files.exists(journal)) {
            return;
        }
        // Only delete known transaction files, never an arbitrary or recursive path.
        for (String name : FILES) {
            Files.deleteIfExists(journal.resolve(name + ".old"));
            Files.deleteIfExists(journal.resolve(name + ".new"));
        }
        Files.deleteIfExists(journal.resolve("prepared"));
        Files.deleteIfExists(journal.resolve("committed"));
        Files.delete(journal);
    }

    // Restore files from an interrupted transaction before cleanup.
    private void recover() throws IOException {
        if (!Files.exists(journal)) {
            return;
        }
        if (Files.exists(journal.resolve("prepared")) && !Files.exists(journal.resolve("committed"))) {
            ArrayList<String> existed = new ArrayList<>(Files.readAllLines(journal.resolve("prepared")));
            for (String name : FILES) {
                Path old = journal.resolve(name + ".old");
                if (existed.contains(name)) {
                    // Copy instead of move so recovery itself can be retried after an interruption.
                    Files.copy(old, file(name), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(file(name));
                }
            }
        }
        cleanJournal();
    }

    // Save related files together with locking and interrupted-save recovery.
    synchronized void commit(Map<String, String> contents) {
        boolean startedJournal = false;
        try (FileChannel channel = lockChannel(); FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw new IOException("Another process is saving these files.");
            }
            recover();
            for (String name : FILES) {
                Path target = file(name);
                if (Files.exists(target) && !Files.isRegularFile(target)) {
                    throw new IOException(name + " must be a regular file.");
                }
                byte[] current = null;
                if (Files.exists(target)) {
                    current = Files.readAllBytes(target);
                }
                if (!Arrays.equals(current, original.get(name))) {
                    throw new IOException(name + " changed outside this session. Restart to reload it.");
                }
            }
            Files.createDirectory(journal);
            startedJournal = true;
            ArrayList<String> existed = new ArrayList<>();
            for (String name : FILES) {
                if (original.get(name) != null) {
                    existed.add(name);
                    durableWrite(journal.resolve(name + ".old"), original.get(name));
                }
                durableWrite(journal.resolve(name + ".new"), contents.get(name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            durableWrite(journal.resolve("prepared"), String.join("\n", existed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int replaced = 0;
            for (String name : FILES) {
                replace(journal.resolve(name + ".new"), file(name));
                replaced++;
                if (replaced == Integer.getInteger("q2p.test.failAfterReplace", -1)) {
                    throw new IOException("Injected save failure.");
                }
                if (replaced == Integer.getInteger("q2p.test.crashAfterReplace", -1)) {
                    Runtime.getRuntime().halt(91);
                }
            }
            durableWrite(journal.resolve("committed"), new byte[0]);
            rememberFiles();
            // A completed commit remains valid even if journal cleanup must wait until restart.
            try {
                cleanJournal();
            } catch (IOException ignored) {
            }
        } catch (Exception e) {
            if (startedJournal) {
                try (FileChannel channel = lockChannel(); FileLock lock = channel.tryLock()) {
                    if (lock == null) {
                        throw new IOException("Recovery needs the data lock. Restart before making more changes.");
                    }
                    recover();
                } catch (IOException recovery) {
                    e.addSuppressed(recovery);
                }
            }
            throw new StorageException("Changes were not saved. " + e.getMessage(), e);
        }
    }
}

