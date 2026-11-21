package dev.foliate.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;

class FileErrorsTest {

    private static final String PATH = "/srv/minecraft/plugins/Foliate/config.yml";

    @Test
    void reasonsNeverNameTheFile() {
        assertEquals("permission denied", FileErrors.reason(new AccessDeniedException(PATH)));
        assertEquals("a file or folder is missing", FileErrors.reason(new NoSuchFileException(PATH)));
        assertEquals("Read-only file system",
                FileErrors.reason(new FileSystemException(PATH, null, "Read-only file system")));
        assertEquals("the file system refused it", FileErrors.reason(new FileSystemException(PATH)));
        assertEquals("the file system refused it", FileErrors.reason(new IOException(PATH)));
    }
}
