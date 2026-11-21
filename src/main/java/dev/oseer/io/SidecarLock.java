package dev.oseer.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exclusive, non-waiting ownership of a retained filesystem lock file. */
public final class SidecarLock implements AutoCloseable {
    private static final Set<Path> OPEN = ConcurrentHashMap.newKeySet();

    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SidecarLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    /** Returns null when ownership is held in this JVM or by another process. */
    public static SidecarLock tryAcquire(Path file) throws IOException {
        Path path = canonical(file);
        // On POSIX, closing a second descriptor can release the JVM's first OS lock.
        if (!OPEN.add(path)) return null;

        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock != null) return new SidecarLock(path, channel, lock);
        } catch (OverlappingFileLockException busy) {
            // The JVM can also detect overlap with a lock opened outside this class.
        } catch (IOException | RuntimeException | Error failure) {
            try {
                release(path, channel, lock);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        release(path, channel, lock);
        return null;
    }

    private static Path canonical(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (NoSuchFileException absent) {
            return normalized.getParent().toRealPath().resolve(normalized.getFileName());
        }
    }

    @Override public void close() throws IOException {
        if (closed.compareAndSet(false, true)) release(path, channel, lock);
    }

    private static void release(Path path, FileChannel channel, FileLock lock) throws IOException {
        try (channel) {
            if (lock != null) lock.release();
        } finally {
            // Keep the JVM guard until the lock is released and the channel is closed.
            OPEN.remove(path);
        }
    }
}
