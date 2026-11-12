package org.pgledger.hook.keys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Objects;
import org.pgledger.hook.keys.SessionKeyException.Reason;

/**
 * The Ed25519 key pairs of the authors, one file per (MCP server, author) in one folder. This package is the only place
 * of the hook that uses file system APIs. The store finds the folder and orders the steps; each step is a class of its
 * own, which is the reason that changes it: {@link SessionKeyFile} (the name and the content of the file),
 * {@link Protection} (owner and access of the folder and the files), {@link KeyFileReader} (open and read an existing
 * file safely) and {@link KeyFileWriter} (create a new file without a race).
 *
 * <p>Why per server: the hook signs for the MCP server named in the host's tool name, and the owner registers the
 * public key of each server separately, so a signature made for one server (the test server, a look-alike server) never
 * verifies on another: a key registered on one server is simply another key than the one of the other.
 *
 * <p>An existing file is read and checked; it is never overwritten, repaired or deleted. A missing file is created
 * with a new key pair; if another process created it in the meantime, that process's file is read.
 */
public final class SessionKeyStore {

    private final KeyFolder folder;
    private final String userName;

    /**
     * A store in this folder, for the key files of the operating system account {@code userName}; nothing is created
     * before the first {@link #load}. A folder that is not usable does not fail here: every {@link #load} of that store
     * refuses with {@link Reason#FOLDER_UNAVAILABLE}, so the hook can answer the host.
     */
    public SessionKeyStore(KeyFolder folder, String userName) {
        this.folder = Objects.requireNonNull(folder, "folder");
        this.userName = userName;
    }

    /**
     * The key pair of the author for the server: the one in the key folder, or a new one that is stored there first.
     *
     * @throws SessionKeyException when the folder or the file cannot be used; the message holds no key material
     */
    public SessionKey load(String server, String author) {
        SessionKeyFile keyFile = new SessionKeyFile(server, author);
        Path directory = directory();
        try {
            Protection protection = Protection.of(directory.getFileSystem(), userName);
            prepareDirectory(directory, protection);
            Path file = directory.resolve(keyFile.fileName());
            KeyFileReader reader = new KeyFileReader(protection);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return read(reader, file, keyFile);
            }
            return create(reader, new KeyFileWriter(protection), file, keyFile);
        } catch (IOException failure) {
            throw new SessionKeyException(Reason.IO_FAILED, failure);
        }
    }

    /** The folder, or the refusal that says why there is none. */
    private Path directory() {
        return switch (folder) {
            case KeyFolder.Usable usable -> usable.directory();
            case KeyFolder.Unconfigured _ -> throw new SessionKeyException(Reason.FOLDER_UNAVAILABLE);
            case KeyFolder.Rejected rejected -> throw new SessionKeyException(Reason.FOLDER_UNAVAILABLE, rejected.cause());
        };
    }

    private static void prepareDirectory(Path directory, Protection protection) throws IOException {
        if (!Files.exists(directory)) {
            // parents that are created here get the same owner-only protection, given at creation
            Files.createDirectories(directory, protection.folderAttributes());
            protection.settle(directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new SessionKeyException(Reason.FOLDER_UNAVAILABLE);
        }
        protection.requireOwnerOnly(directory);
    }

    private static SessionKey read(KeyFileReader reader, Path file, SessionKeyFile keyFile) throws IOException {
        byte[] content = reader.read(file);
        try {
            return keyFile.parse(content);
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    private static SessionKey create(KeyFileReader reader, KeyFileWriter writer, Path file, SessionKeyFile keyFile)
            throws IOException {
        KeyPair pair = keyFile.newKeyPair();
        byte[] content = keyFile.encode(pair);
        try {
            if (writer.createNew(file, content)) {
                return keyFile.key(pair);
            }
        } finally {
            Arrays.fill(content, (byte) 0);
        }
        // another hook process created the file after the check in load: its key is the key of the author
        return read(reader, file, keyFile);
    }
}
