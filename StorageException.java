// Custom exception for errors when reading or saving data files.
class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    StorageException(String message, Throwable cause) {
        super(message, cause);
    }
    StorageException(String message) {
        super(message);
    }
}

