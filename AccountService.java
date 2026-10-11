import java.util.ArrayList;
// Accounts share the same recoverable file commit as inventory and rentals.
class AccountService {
    private final ArrayList<User> users;
    private final ConsoleUI ui;
    private final RentalDataStore dataStore;
    // This counter belongs to the running application, not one login screen.
    private int failedLoginAttempts;
    private final String unknownPasswordHash = Passwords.hashPlaintext("Unknown account");

    AccountService(ArrayList<User> users, ConsoleUI ui, RentalDataStore dataStore) {
        this.users = users;
        this.ui = ui;
        this.dataStore = dataStore;
    }

    static boolean validUsername(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{3,32}") && !value.equalsIgnoreCase("C") && !value.equals("0");
    }

    static boolean validName(String value) {
        return value != null && value.length() <= 100 && value.matches("[\\p{L}][\\p{L}\\p{M} .'-]{1,99}");
    }

    static boolean validEmail(String value) {
        return value != null && value.length() <= 254 && value.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    // Keep the older validators above for saved accounts. New accounts use these rules.
    static boolean validRegistrationName(String value) {
        if (value == null || value.length() < 5 || value.length() > 60) {
            return false;
        }
        int letters = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isLetter(character)) {
                letters++;
            } else if (character != ' ' && character != '.') {
                return false;
            }
        }
        return letters >= 2 && Character.isLetter(value.charAt(0));
    }

    static boolean validRegistrationUsername(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{3,20}")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static boolean validRegistrationEmail(String value) {
        if (!validEmail(value) || value.length() > 100) {
            return false;
        }
        int at = value.indexOf('@');
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        if (local.startsWith(".") || local.endsWith(".") || value.contains("..")) {
            return false;
        }
        String[] labels = domain.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    static boolean validRegistrationPassword(String value) {
        if (value == null || value.length() < 8 || value.length() > 20) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '|' || Character.isWhitespace(character)
                    || Character.isSpaceChar(character) || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    private boolean embeddedAccountsChanged;

    boolean embeddedAccountsChanged() {
        return embeddedAccountsChanged;
    }

    void loadAccounts() {
        for (RentalDataStore.Record record : dataStore.records("accounts.txt")) {
            String[] fields = record.fields;
            try {
                RentalDataStore.require(fields.length == 6, "Expected six account fields");
                RentalDataStore.require(validUsername(fields[0]) && validName(fields[1]) && validEmail(fields[2]), "Invalid account name or login identifier");
                RentalDataStore.require(fields[4].equals("ADMIN") || fields[4].equals("CLIENT"), "Invalid role");
                RentalDataStore.require(fields[5].equals("REGULAR") || fields[5].equals("VIP"), "Invalid membership");
                RentalDataStore.require(fields[3].length() >= 8 && !GameCatalogService.containsControlCharacter(fields[3]), "Invalid stored password");
                RentalDataStore.require(findUserByLogin(fields[0]) == null && findUserByLogin(fields[2]) == null && !fields[0].equalsIgnoreCase(fields[2]), "Duplicate login identifier");
                users.add(new User(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]));
            } catch (IllegalArgumentException e) {
                throw dataStore.invalidRecord("accounts.txt", record, e);
            } catch (java.time.DateTimeException e) {
                throw dataStore.invalidRecord("accounts.txt", record, e);
            }
        }
        ensureEmbeddedAccount("admin", "System Admin", "admin@store.com", "admin123", "ADMIN", "REGULAR");
        ensureEmbeddedAccount("alex", "Alex R. Reyes", "alex@email.com", "alex12345", "CLIENT", "VIP");
        ensureEmbeddedAccount("juan", "Juan D. Cruz", "juan@email.com", "juan12345", "CLIENT", "REGULAR");
    }

    private void ensureEmbeddedAccount(String username, String name, String email, String password, String role, String membership) {
        User account = findUser(username);
        User emailOwner = findUserByLogin(email);
        RentalDataStore.require(emailOwner == null || emailOwner == account, "Embedded account email is already used: "+email);
        if (account == null) {
            User added = new User(username, name, email, password, role, membership);
            if (role.equals("ADMIN")) {
                users.add(0, added);
            } else {
                users.add(added);
            }
            embeddedAccountsChanged = true;
            return;
        }
        if (!account.username.equals(username) || !account.fullName.equals(name) || !account.email.equals(email) || !account.role.equals(role)) {
            embeddedAccountsChanged = true;
        }
        account.username = username;
        account.fullName = name;
        account.email = email;
        account.role = role;
        if (!Passwords.matches(password, account.password)) {
            account.password = Passwords.hashPlaintext(password);
            embeddedAccountsChanged = true;
        }
        // Keep saved client upgrades and rental references when restoring the embedded logins.
        if (role.equals("ADMIN") && !account.membership.equals(membership)) {
            account.membership = membership;
            embeddedAccountsChanged = true;
        }
    }

    void saveAccounts() {
        dataStore.saveData();
    }

    User findUser(String username) {
        for (User user : users) {
            if (user.username.equalsIgnoreCase(username)) {
                return user;
            }
        }
        return null;
    }

    User findUserByLogin(String login) {
        // Deterministic username lookup, with uniqueness enforced when registering/loading.
        User byName = findUser(login);
        if (byName != null) {
            return byName;
        }
        for (User user : users) {
            if (user.email.equalsIgnoreCase(login)) {
                return user;
            }
        }
        return null;
    }

    boolean emailExists(String email) {
        return findUserByLogin(email) != null;
    }

    private boolean cancelRegistration() {
        if (ui.confirmRegistrationCancellation()) {
            ui.info("Registration cancelled. No account was created.");
            return true;
        }
        return false;
    }

    void register() {
        String fullName = "";
        String username = "";
        String email = "";
        String password = "";
        String pendingPassword = "";
        int step = 1;
        boolean editing = false;
        while (true) {
            ui.clearScreen();
            if (step == 6) {
                ui.showRegistrationConfirmation(fullName, username, email);
                ui.inputBar("ENTER CHOICE");
                String choice = ui.readText(ui.prompt()).trim();
                if (choice.equals("1")) {
                    break;
                }
                if (choice.equalsIgnoreCase("E")) {
                    step = 7;
                } else if (choice.equalsIgnoreCase("C")) {
                    if (cancelRegistration()) {
                        return;
                    }
                } else {
                    ui.error("Use 1 to create, E to edit, or C to cancel.");
                }
                continue;
            }
            if (step == 7) {
                ui.showRegistrationEditMenu(fullName, username, email);
                String choice = ui.readText(ui.prompt()).trim();
                if (choice.equalsIgnoreCase("B")) {
                    step = 6;
                } else if (choice.equals("1") || choice.equals("2")
                        || choice.equals("3") || choice.equals("4")) {
                    step = Integer.parseInt(choice);
                    editing = true;
                } else {
                    ui.error("Choose 1, 2, 3, 4, or B to return to the summary.");
                }
                continue;
            }

            ui.showRegistrationStep(step, editing, fullName, username, email);
            String value;
            if (step == 4 || step == 5) {
                value = ui.readPassword(ui.prompt());
            } else {
                value = ui.readText(ui.prompt()).trim();
            }
            if (step == 1 && !editing && (value.equalsIgnoreCase("B") || value.equals("0"))) {
                if (!fullName.isEmpty() && !cancelRegistration()) {
                    continue;
                }
                return;
            }
            if ((step > 1 || editing) && value.equalsIgnoreCase("C")) {
                if (cancelRegistration()) {
                    return;
                }
                continue;
            }
            if ((step > 1 || editing) && value.equalsIgnoreCase("B")) {
                if (step == 5) {
                    step = 4;
                } else if (editing) {
                    step = 7;
                    editing = false;
                } else {
                    step--;
                }
                continue;
            }
            if (step == 1) {
                if (!validRegistrationName(value)) {
                    ui.showFullNameInstructions();
                    continue;
                }
                fullName = value;
            } else if (step == 2) {
                if (!validRegistrationUsername(value)) {
                    ui.error("Use 3-20 letters, digits, dots, underscores or hyphens, with at least one letter.");
                    continue;
                }
                if (findUserByLogin(value) != null) {
                    ui.error("Username is already taken. Please try another one.");
                    continue;
                }
                username = value;
            } else if (step == 3) {
                if (!validRegistrationEmail(value)) {
                    ui.error("Enter a valid email of at most 100 characters. Example: juan@gmail.com");
                    continue;
                }
                if (emailExists(value)) {
                    ui.error("Email is already registered. Please use another email.");
                    continue;
                }
                email = value;
            } else if (step == 4) {
                if (!validRegistrationPassword(value)) {
                    ui.error("Use 8-20 characters without whitespace, control characters or |.");
                    continue;
                }
                // Retain the previously confirmed password until the replacement is confirmed.
                pendingPassword = value;
                step = 5;
                continue;
            } else {
                if (!value.equals(pendingPassword)) {
                    ui.error("Passwords do not match. Please try again.");
                    continue;
                }
                password = pendingPassword;
            }
            if (editing || step == 5) {
                step = 6;
                editing = false;
            } else {
                step++;
            }
        }
        ui.loading("Creating account");
        users.add(new User(username, fullName, email, Passwords.hashPlaintext(password), "CLIENT", "REGULAR"));
        dataStore.writeLog("EVENT", "Registered account: " + username);
        saveAccounts();
        ui.clearScreen();
        ui.showAccountCreated();
        ui.finishScreen();
        ui.waitForResultNavigation("", "Main Menu");
    }

    private void failedLogin(String login) {
        failedLoginAttempts++;
        ui.error("Incorrect username/email or password.");
        ui.showLoginAttempt((failedLoginAttempts - 1) % 3 + 1);
        if (failedLoginAttempts % 3 == 0) {
            ui.showLoginCooldown();
        }
        dataStore.writeLog("EVENT", "Failed login attempt for account: "
                + login.replaceAll("[\\p{Cntrl}|]", "?"));
        dataStore.saveData();
    }

    User login() {
        while (true) {
            ui.clearScreen();
            ui.showLoginUsernameScreen();
            String login = ui.readText(ui.prompt()).trim();
            if (login.equalsIgnoreCase("B") || login.equals("0")) {
                return null;
            }
            if (login.isEmpty()) {
                ui.error("Username or email cannot be empty.");
                continue;
            }
            User user = findUserByLogin(login);
            while (true) {
                ui.clearScreen();
                // Show the supplied identifier for every account, including unknown ones.
                ui.showLoginPasswordScreen(login);
                String password = ui.readPassword(ui.prompt());
                if (password.equalsIgnoreCase("C") || password.equals("0")) {
                    return null;
                }
                if (password.equalsIgnoreCase("B")) {
                    break;
                }
                if (password.isEmpty()) {
                    ui.error("Password cannot be empty.");
                    continue;
                }
                ui.loading("Verifying account");
                String storedPassword = unknownPasswordHash;
                if (user != null) {
                    storedPassword = user.password;
                }
                boolean matches = Passwords.matches(password, storedPassword);
                if (user == null || !matches) {
                    failedLogin(login);
                    continue;
                }
                dataStore.writeLog("EVENT", "Login: " + user.username + " (" + user.role + ")");
                dataStore.saveData();
                failedLoginAttempts = 0;
                ui.success("LOGIN SUCCESSFUL - Welcome, " + user.fullName + "!");
                ui.pause(500);
                ui.loading("Loading dashboard");
                return user;
            }
        }
    }
}
