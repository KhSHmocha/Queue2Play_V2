import java.util.Stack;

class User {
    String username;
    String fullName;
    String email;
    String password;
    String role;        // CLIENT or ADMIN
    String membership;  // REGULAR or VIP

    // Stack keeps the newest rental or cancellation record on top for history display.
    Stack<Rental> rentalHistory = new Stack<>();

    // Store account details and hash the password before keeping it.
    User(String username, String fullName, String email,
         String password, String role, String membership) {
        this.username = username;
        this.fullName = fullName;
        this.email = email;
        this.password = PasswordSecurity.hash(password);
        this.role = role;
        this.membership = membership;
    }

    // Check whether the account has VIP membership.
    boolean isVip() {
        return membership.equals("VIP");
    }

    // Check whether the account can access administrator functions.
    boolean isAdmin() {
        return role.equals("ADMIN");
    }
}


