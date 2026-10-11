public class Main {
    public static void main(String[] args) {
        try {
            Queue2Play system = new Queue2Play();
            system.start();
        } catch (InputEndedException e) {
            System.out.println("Input ended. Pending input was not submitted.");
        } catch (StorageException e) {
            System.err.println("QUEUE2PLAY: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("QUEUE2PLAY: " + e.getMessage());
            System.exit(1);
        }
    }
}
