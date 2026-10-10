class Game {
    String gameId;
    String title;
    String platform;
    double price;
    int availableCopies;

    Game(String gameId, String title, String platform, double price, int availableCopies) {
        this.gameId = gameId;
        this.title = title;
        this.platform = platform;
        this.price = price;
        this.availableCopies = availableCopies;
    }

    boolean isAvailable() {
        return availableCopies > 0;
    }

}

