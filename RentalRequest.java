class RentalRequest {
    int queueNumber;
    User client;
    Game game;
    boolean vip;            // membership when the request was made
    int rentalDays = Queue2Play.RENTAL_DAYS;   // rental length chosen in the order
    int quantity = 1;       // copies of the game requested in the order


    // Store the client, game and membership for a queued order line.
    RentalRequest(int queueNumber, User client, Game game) {
        this.queueNumber = queueNumber;
        this.client = client;
        this.game = game;
        this.vip = client.isVip();
    }
}

