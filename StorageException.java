// Custom exception for errors when reading or saving data files.
class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    // Carry a file error and, when supplied, its original cause.
    StorageException(String message, Throwable cause) {
        super(message, cause);
    }
    // Carry a file error and, when supplied, its original cause.
    StorageException(String message) {
        super(message);
    }
}

