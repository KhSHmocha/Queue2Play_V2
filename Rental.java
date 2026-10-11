import java.time.LocalDateTime;

class Rental implements Comparable<Rental> {
    int rentalNumber;
    User client;
    Game game;
    LocalDateTime checkoutDate;
    LocalDateTime dueDate;
    double amount;
    String status;   // ACTIVE, COMPLETED or CANCELLED (history only)
    int quantity = 1;   // number of copies rented in this order line
    LocalDateTime returnDate;
    int daysLate;
    double lateFee;

    // Store rental dates, quantity, amount and current status.
    Rental(int rentalNumber, User client, Game game,
           LocalDateTime checkoutDate, LocalDateTime dueDate, double amount) {
        this.rentalNumber = rentalNumber;
        this.client = client;
        this.game = game;
        this.checkoutDate = checkoutDate;
        this.dueDate = dueDate;
        this.amount = amount;
        this.status = "ACTIVE";
    }

    // Order rentals by due date, then record number, for the min-heap.
    @Override
    public int compareTo(Rental other) {
        int byDate = dueDate.compareTo(other.dueDate);
        if (byDate != 0) {
            return byDate;
        }
        if (rentalNumber < other.rentalNumber) {
            return -1;
        }
        if (rentalNumber > other.rentalNumber) {
            return 1;
        }
        return 0;
    }
}

