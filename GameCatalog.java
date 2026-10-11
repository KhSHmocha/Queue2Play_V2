import java.util.ArrayList;
import java.util.Map;
// Owns inventory lookup and the search/sort algorithms used by the menus.
class GameCatalog {
    static boolean validTitle(String title) {
        return title != null && !title.isBlank() && title.length() <= 100 && !title.contains("|")
        && !containsControlCharacter(title);
    }

    static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    static boolean validId(String id, String platform) {
        String prefix;
        if (platform.equals("PS5")) {
            prefix = "P";
        } else if (platform.equals("Xbox")) {
            prefix = "X";
        } else if (platform.equals("Nintendo")) {
            prefix = "N";
        } else {
            return false;
        }
        if (id == null || !id.matches(prefix + "[0-9]{3,10}")) {
            return false;
        }
        try {
            int n = Integer.parseInt(id.substring(1));
            return n > 0 && n < Integer.MAX_VALUE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static void validate(String title, String platform, double price, int stock) {
        RentalDataStore.require(validTitle(title), "Invalid game title");
        RentalDataStore.require(platform.equals("PS5") || platform.equals("Xbox") || platform.equals("Nintendo"), "Invalid platform");
        RentalDataStore.require(Double.isFinite(price) && price >= Queue2Play.MIN_RENTAL_PRICE
            && price <= Queue2Play.MAX_RENTAL_PRICE, "Invalid rental price");
        RentalDataStore.require(stock >= 0 && stock <= Queue2Play.MAX_GAME_STOCK, "Invalid stock");
    }

    private final Map<String, Game> games;
    int ps5Count;
    int xboxCount;
    int nintendoCount;

    GameCatalog(Map<String, Game> games) {
        this.games = games;
    }

    String generateGameId(String platform) {
        RentalDataStore.require(platform.equals("PS5") || platform.equals("Xbox") || platform.equals("Nintendo"), "Invalid platform");
        RentalDataStore.require(ps5Count < Integer.MAX_VALUE - 1 && xboxCount < Integer.MAX_VALUE - 1
            && nintendoCount < Integer.MAX_VALUE - 1, "Game ID range exhausted");
        if (platform.equals("PS5")) {
            ps5Count++;
            return String.format("P%03d", ps5Count);
        } else if (platform.equals("Xbox")) {
            xboxCount++;
            return String.format("X%03d", xboxCount);
        } else {
            nintendoCount++;
            return String.format("N%03d", nintendoCount);
        }
    }

    Game addGame(String title, String platform, double price, int copies) {
        validate(title, platform, price, copies);
        for (Game existing : games.values()) {
            RentalDataStore.require(!existing.title.equalsIgnoreCase(title) || !existing.platform.equals(platform),
                "This game already exists on this platform");
        }
        String id = generateGameId(platform);
        Game game = new Game(id, title, platform, price, copies);
        games.put(id, game);
        return game;
    }

    void loadStartingGames() {
        addGame("Spider-Man 2", "PS5", 5.00, 2);
        addGame("Minecraft", "PS5", 4.00, 1);
        addGame("God of War", "PS5", 5.50, 1);
        addGame("Halo Infinite", "Xbox", 4.50, 1);
        addGame("Forza Horizon 5", "Xbox", 5.00, 2);
        addGame("Minecraft", "Xbox", 4.00, 1);
        addGame("Mario Kart 8", "Nintendo", 5.00, 2);
        addGame("Zelda: Tears of the Kingdom", "Nintendo", 6.00, 1);
        addGame("Animal Crossing", "Nintendo", 4.50, 1);
    }

    ArrayList<Game> getGameList(String platform) {
        ArrayList<Game> list = new ArrayList<>();
        for (Game game : games.values()) {
            if (platform.equals("ALL") || game.platform.equals(platform)) {
                list.add(game);
            }
        }
        return list;
    }

    // Linear search checks each game in the selected list for a matching ID, title, or platform.
    ArrayList<Game> linearSearch(ArrayList<Game> list, String keyword) {
        ArrayList<Game> results = new ArrayList<>();
        String key = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        for (Game game : list) {
            if (game.gameId.toLowerCase(java.util.Locale.ROOT).equals(key)
                || game.title.toLowerCase(java.util.Locale.ROOT).contains(key)
                || game.platform.toLowerCase(java.util.Locale.ROOT).equals(key)) {
                results.add(game);
            }
        }
        return results;
    }

    // Build a fresh view every time: searching always uses the full platform list.
    ArrayList<Game> getDisplayList(ArrayList<Game> platformGames, String keyword, int sortOption) {
        ArrayList<Game> displayGames = linearSearch(platformGames, keyword);
        if (sortOption != 0) {
            insertionSort(displayGames, sortOption);
        }
        return displayGames;
    }

    // Insertion sort shifts games until each one is placed at its selected title or price position.
    void insertionSort(ArrayList<Game> list, int option) {
        RentalDataStore.require(option >= 1 && option <= 4, "Invalid sort option");
        for (int i = 1; i < list.size(); i++) {
            Game currentGame = list.get(i);
            int j = i - 1;
            while (j >= 0 && shouldMoveRight(list.get(j), currentGame, option)) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, currentGame);
        }
    }

    private boolean shouldMoveRight(Game left, Game current, int option) {
        if (option == 1) {
            return left.title.compareToIgnoreCase(current.title) > 0;
        } else if (option == 2) {
            return left.title.compareToIgnoreCase(current.title) < 0;
        } else if (option == 3) {
            return left.price > current.price;
        } else {
            return left.price < current.price;
        }
    }

    void updateCounterFromId(String id) {
        if (id.length() < 2) {
            return;
        }
        int number;
        try {
            number = Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return;
        }
        if (id.startsWith("P")) {
            ps5Count = Math.max(ps5Count, number);
        } else if (id.startsWith("X")) {
            xboxCount = Math.max(xboxCount, number);
        } else if (id.startsWith("N")) {
            nintendoCount = Math.max(nintendoCount, number);
        }
    }
}
