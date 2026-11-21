package dev.foliate.core.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

/**
 * What went wrong with a file, in words that can be shown to whoever ran a command. The messages
 * of {@link IOException} name the file by its absolute path, and the sender of
 * {@code /foliate reload} has no business knowing the server's folders: the caller names the file
 * itself, relative to the data folder, and adds the reason given here.
 */
public final class FileErrors {

    private FileErrors() {}

    /** Such as {@code permission denied}, or the file system's own reason; never a path. */
    public static String reason(IOException failure) {
        return switch (failure) {
            case AccessDeniedException denied -> "permission denied";
            case NoSuchFileException missing -> "a file or folder is missing";
            case FileSystemException other when other.getReason() != null -> other.getReason();
            default -> "the file system refused it";
        };
    }
}
