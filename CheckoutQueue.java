import java.util.ArrayList;
import java.util.LinkedHashMap;

/** A doubly linked checkout queue. VIP requests precede regular requests. */
public class CheckoutQueue {
    private static class Node {
        final RentalRequest request;
        Node previous;
        Node next;
        Node(RentalRequest request) {
            this.request = request;
        }
    }

    private Node front;
    private Node rear;
    static final int VIP_BURST = 3;
    int vipStreak;

    void clear() {
        front = null;
        rear = null;
    }

    void refreshMembership(User client) {
        ArrayList<RentalRequest> requests = getRequests();
        for (RentalRequest request : requests) {
            if (request.client == client) {
                request.vip = client.isVip();
            }
        }
        sortByQueueNumber(requests);
        clear();
        for (RentalRequest request : requests) {
            enqueue(request);
        }
    }

    // Insertion sort restores arrival order before rebuilding VIP/regular groups.
    static void sortByQueueNumber(ArrayList<RentalRequest> requests) {
        for (int i = 1; i < requests.size(); i++) {
            RentalRequest current = requests.get(i);
            int j = i - 1;
            while (j >= 0 && requests.get(j).queueNumber > current.queueNumber) {
                requests.set(j + 1, requests.get(j));
                j--;
            }
            requests.set(j + 1, current);
        }
    }

    static boolean validRequest(RentalRequest request) {
        return request != null && request.client != null && request.game != null && !request.client.isAdmin()
            && request.queueNumber > 0 && request.quantity >= 1 && request.quantity <= Queue2Play.MAX_SAVED_COPIES_PER_GAME
            && request.rentalDays >= 1 && request.rentalDays <= Queue2Play.MAX_RENTAL_DAYS;
    }

    void recordCheckout(RentalRequest request) {
        if (request.vip) {
            if (vipStreak < VIP_BURST) {
                vipStreak++;
            }
        } else {
            vipStreak = 0;
        }
    }

    boolean isEmpty() {
        return front == null;
    }

    void enqueue(RentalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null.");
        }
        Node node = new Node(request);
        if (isEmpty()) {
            front = node;
            rear = node;
            return;
        }
        if (request.vip) {
            Node current = front;
            // Insert after existing VIPs to preserve FIFO within the priority.
            while (current != null && current.request.vip) {
                current = current.next;
            }
            if (current != null) {
                insertBefore(current, node);
                return;
            }
        }
        rear.next = node;
        node.previous = rear;
        rear = node;
    }

    private void insertBefore(Node target, Node node) {
        node.next = target;
        node.previous = target.previous;
        if (target.previous == null) {
            front = node;
        } else {
            target.previous.next = node;
        }
        target.previous = node;
    }

    void remove(RentalRequest request) {
        for (Node current = front; current != null; current = current.next) {
            if (current.request == request) {
                unlink(current);
                return;
            }
        }
    }

    private void unlink(Node node) {
        if (node.previous == null) {
            front = node.next;
        } else {
            node.previous.next = node.next;
        }
        if (node.next == null) {
            rear = node.previous;
        } else {
            node.next.previous = node.previous;
        }
        node.previous = null;
        node.next = null;
    }

    int countRequestsForGame(Game game) {
        int count = 0;
        for (Node current = front; current != null; current = current.next) {
            if (RentalManager.sameGame(current.request.game, game)) {
                count++;
            }
        }
        return count;
    }

    int removeRequestsForGame(Game game) {
        int removed = 0;
        Node current = front;
        while (current != null) {
            Node next = current.next;
            if (RentalManager.sameGame(current.request.game, game)) {
                unlink(current);
                removed++;
            }
            current = next;
        }
        return removed;
    }

    boolean hasRequest(User client, Game game) {
        for (Node current = front; current != null; current = current.next) {
            if (RentalManager.sameUser(current.request.client, client) && RentalManager.sameGame(current.request.game, game)) {
                return true;
            }
        }
        return false;
    }

    int queuedCopies(User client, Game game) {
        int copies = 0;
        for (Node current = front; current != null; current = current.next) {
            RentalRequest request = current.request;
            if (RentalManager.sameUser(request.client, client) && RentalManager.sameGame(request.game, game)) {
                copies += request.quantity;
            }
        }
        return copies;
    }

    RentalRequest findByNumber(int queueNumber) {
        for (Node current = front; current != null; current = current.next) {
            if (current.request.queueNumber == queueNumber) {
                return current.request;
            }
        }
        return null;
    }

    ArrayList<RentalRequest> getRequestsOf(User client) {
        ArrayList<RentalRequest> requests = new ArrayList<>();
        for (Node current = front; current != null; current = current.next) {
            if (RentalManager.sameUser(current.request.client, client)) {
                requests.add(current.request);
            }
        }
        return requests;
    }

    int getPosition(RentalRequest request) {
        return previewReadyRequests().indexOf(request) + 1;
    }

    // Ready requests come first; waiting requests keep their existing relative order.
    ArrayList<RentalRequest> getDisplayRequests(ArrayList<RentalRequest> ready) {
        ArrayList<RentalRequest> displayed = new ArrayList<>(ready);
        for (RentalRequest request : getRequests()) {
            if (!ready.contains(request)) {
                displayed.add(request);
            }
        }
        return displayed;
    }

    RentalRequest findNextEligibleRequest() {
        if (vipStreak >= VIP_BURST) {
            for (Node current = front; current != null; current = current.next) {
                RentalRequest request = current.request;
                if (validRequest(request) && !request.vip && request.game.availableCopies >= request.quantity) {
                    return request;
                }
            }
        }
        for (Node current = front; current != null; current = current.next) {
            RentalRequest request = current.request;
            if (validRequest(request) && request.game.availableCopies >= request.quantity) {
                return request;
            }
        }
        return null;
    }

    // A snapshot lets screens and file storage read requests without accessing links.
    ArrayList<RentalRequest> getRequests() {
        ArrayList<RentalRequest> requests = new ArrayList<>();
        for (Node current = front; current != null; current = current.next) {
            requests.add(current.request);
        }
        return requests;
    }
    // Use the real queue policy on copied games; previews never reserve stock.
    ArrayList<RentalRequest> previewReadyRequests() {
        CheckoutQueue previewQueue = new CheckoutQueue();
        previewQueue.vipStreak = vipStreak;
        LinkedHashMap<Game, Game> stockCopies = new LinkedHashMap<>();
        for (RentalRequest request : getRequests()) {
            if (!CheckoutQueue.validRequest(request)) {
                continue;
            }
            Game copiedGame = stockCopies.get(request.game);
            if (copiedGame == null && request.game != null) {
                copiedGame = new Game(request.game.gameId, request.game.title, request.game.platform,
                    request.game.price, request.game.availableCopies);
                stockCopies.put(request.game, copiedGame);
            }
            RentalRequest copiedRequest = new RentalRequest(request.queueNumber, request.client, copiedGame);
            copiedRequest.vip = request.vip;
            copiedRequest.quantity = request.quantity;
            copiedRequest.rentalDays = request.rentalDays;
            previewQueue.enqueue(copiedRequest);
        }
        ArrayList<RentalRequest> planned = new ArrayList<>();
        RentalRequest next = previewQueue.findNextEligibleRequest();
        while (next != null) {
            planned.add(findByNumber(next.queueNumber));
            next.game.availableCopies -= next.quantity;
            previewQueue.recordCheckout(next);
            previewQueue.remove(next);
            next = previewQueue.findNextEligibleRequest();
        }
        return planned;
    }

}
