class Game {
    String gameId;
    String title;
    String platform;
    double price;
    int availableCopies;

    // Store a game's identifier, platform, price and available stock.
    Game(String gameId, String title, String platform, double price, int availableCopies) {
        this.gameId = gameId;
        this.title = title;
        this.platform = platform;
        this.price = price;
        this.availableCopies = availableCopies;
    }

    // Check whether a game has any available copies.
    boolean isAvailable() {
        return availableCopies > 0;
    }

}

