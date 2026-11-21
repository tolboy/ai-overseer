import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;

/** Build the same dependency-free portable distribution from either host launcher. */
final class PortablePackage {
    private static final String MAIN_CLASS = "dev.oseer.Main";
    private static final String ARCHIVE = "ai-overseer.zip";
    private static final List<String> SHIPPED_FILES = List.of(
            "LICENSE", "NOTICE", "bin/oseer", "bin/oseer.cmd", "scripts/visual-qa.mjs",
            "examples/pilot/policy.yaml");
    private static final LocalDateTime ARCHIVE_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("Usage: package.sh or package.cmd");
        Path distribution = Files.createDirectories(Path.of("out", "dist")).toRealPath();
        Path workspace = Files.createTempDirectory(distribution, ".package-");
        try {
            Path classes = Files.createDirectory(workspace.resolve("classes"));
            compile(classes);
            Path jar = workspace.resolve("oseer.jar");
            writeJar(jar, classes);

            Map<String, byte[]> files = new TreeMap<>();
            for (String file : SHIPPED_FILES) files.put(file, Files.readAllBytes(Path.of(file)));
            files.put("lib/oseer.jar", Files.readAllBytes(jar));
            files.put("START-HERE.md", Files.readAllBytes(Path.of("docs/FIRST-RUN.md")));
            String conductor = Files.readString(Path.of("conductor/do.yaml"));
            files.put("conductor/do.yaml",
                    conductor.replace("../out/classes", "../lib/oseer.jar").getBytes(StandardCharsets.UTF_8));

            Path archive = workspace.resolve(ARCHIVE);
            try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
                for (var file : files.entrySet()) write(output, "ai-overseer/" + file.getKey(), file.getValue());
            }
            Path destination = distribution.resolve(ARCHIVE);
            try {
                Files.move(archive, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(archive, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("Packaged " + destination);
        } finally {
            if (!distribution.equals(workspace.toRealPath().getParent())) {
                throw new IOException("Package scratch directory moved outside the output directory");
            }
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void compile(Path classes) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("Packaging requires a JDK 21 or newer on PATH");
        List<Path> sources;
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var options = List.of("--release", "21", "-d", classes.toString());
            var task = compiler.getTask(new PrintWriter(System.err, true), files, null, options, null,
                    files.getJavaFileObjectsFromPaths(sources));
            if (!task.call()) throw new IOException("Compilation failed");
        }
    }

    private static void writeJar(Path jar, Path classes) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            String manifest = "Manifest-Version: 1.0\r\nMain-Class: " + MAIN_CLASS + "\r\n\r\n";
            write(output, "META-INF/MANIFEST.MF", manifest.getBytes(StandardCharsets.UTF_8));
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                write(output, name, Files.readAllBytes(file));
            }
        }
    }

    private static void write(ZipOutputStream output, String name, byte[] data) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTimeLocal(ARCHIVE_TIME);
        // Stored entries remove platform compressor versions from the archive bytes.
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        var checksum = new CRC32();
        checksum.update(data);
        entry.setCrc(checksum.getValue());
        output.putNextEntry(entry);
        output.write(data);
        output.closeEntry();
    }
}
