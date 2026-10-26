package breakeven;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static breakeven.Theme.TS;
import static breakeven.Theme.peso;

/**
 * In-memory data store + business rules. This is the ONLY place that holds data, so when the database
 * arrives only this class (and its add/remove/query methods) needs to be swapped for DB calls.
 *
 * Data is now organised by semester. Every page reads "the semester being viewed" through budget(), claims(),
 * events() and reports(), so switching semesters needs no change in the pages. A closed semester is read-only.
 *
 * OOP [ENCAPSULATION]: every list is private. The outside world gets read-only views and must use the
 * add/remove/transition methods below, so no page can corrupt the data by editing a list directly.
 * OOP [ABSTRACTION]: pages call approve(), flag(), clearClaim()... and never repeat the rules themselves.
 */
final class Store {

    private Store() {}

    // ---------------------------------------------------------------- state (all private)
    private static final List<User> users = Arrays.asList(
            new Treasurer("treasurer", "treasurer123", "F. Fernandes"),   // OOP [POLYMORPHISM]: three different
            new Auditor("auditor", "auditor123", "A. Reyes"),             // subclasses held in one List<User>
            new Moderator("moderator", "moderator123", "Prof. D. Santos"));

    /** Expense types are plain labels on a claim (reporting only). The ceiling now belongs to the event. */
    private static final List<String> EXPENSE_TYPES = Arrays.asList(
            "Venue & Equipment", "Food & Refreshments", "Printing & Materials", "Transportation", "Honoraria & Fees", "Miscellaneous");

    private static final List<Semester> semesters = new ArrayList<>();   // oldest first
    private static final List<LogEntry> logs = new ArrayList<>();        // one audit trail across all semesters

    private static Semester viewing;
    private static User current;
    private static App app;

    // ---------------------------------------------------------------- read-only access
    static List<User> users()          { return Collections.unmodifiableList(users); }
    static List<Semester> semesters()  { return Collections.unmodifiableList(semesters); }
    static List<String> expenseTypes() { return EXPENSE_TYPES; }
    static Semester semester()         { return viewing; }
    static Budget budget()             { return viewing.getBudget(); }
    static List<Event> events()        { return budget().getEvents(); }
    static List<Claim> claims()        { return viewing.getClaims(); }
    static List<Report> reports()      { return viewing.getReports(); }
    static List<LogEntry> logs()       { return Collections.unmodifiableList(logs); }

    static User current()          { return current; }
    static void setCurrent(User u) { current = u; }
    static App app()               { return app; }
    static void setApp(App a)      { app = a; }

    // ---------------------------------------------------------------- semesters
    /** The semester being worked on: the newest one that is still open (or the newest if all are closed). */
    static Semester currentSemester() {
        Semester s = latestOpen();
        return s != null ? s : semesters.get(semesters.size() - 1);
    }

    private static Semester latestOpen() {
        for (int i = semesters.size() - 1; i >= 0; i--) if (!semesters.get(i).isClosed()) return semesters.get(i);
        return null;
    }

    static void viewSemester(Semester s) { viewing = s; }

    /** Pages use this to hide every edit button while a closed (past) semester is on screen. */
    static boolean editable() { return viewing != null && !viewing.isClosed(); }

    /** Closes the open semester (if any) and starts a fresh one with its own total budget. */
    static Semester startSemester(String label, double total) {
        Semester old = latestOpen();
        if (old != null) old.close();
        int n = semesters.size() + 1;
        Semester s = new Semester("SEM-" + n, label, new Budget("BUD-" + n, total));
        semesters.add(s);
        viewing = s;
        return s;
    }

    // ---------------------------------------------------------------- mutation (controlled)
    static void addClaim(Claim c)       { viewing.addClaim(c); }
    static void removeClaim(Claim c)    { viewing.removeClaim(c); }
    static void addEvent(Event e)       { viewing.requireOpen(); budget().addEvent(e); }
    static void removeEvent(Event e)    { viewing.requireOpen(); budget().removeEvent(e); }
    static void addReport(Report r)     { viewing.addReport(r); }
    static void removeReport(Report r)  { viewing.removeReport(r); }

    // ---------------------------------------------------------------- lookups
    static User authenticate(String username, String password) {
        for (User u : users) if (u.authenticate(username, password)) return u;
        return null;
    }

    static Event event(String n) { return budget().find(n); }

    static List<Claim> claimsFor(String ev) {
        List<Claim> l = new ArrayList<>();
        for (Claim c : claims()) if (c.getEvent().equalsIgnoreCase(ev)) l.add(c);
        l.sort(Comparator.comparing(Claim::getRef));
        return l;
    }

    /** Claims that go into a liquidation report. OOP [POLYMORPHISM]: the status decides, not a string compare. */
    static List<Claim> eligible(String ev) {
        List<Claim> l = new ArrayList<>();
        for (Claim c : claimsFor(ev)) if (c.getStatus().isLiquidationEligible()) l.add(c);
        return l;
    }

    /** Every audit-log line that mentions this claim: the claim's paper trail (newest first). */
    static List<LogEntry> historyFor(Claim c) {
        List<LogEntry> l = new ArrayList<>();
        for (LogEntry e : logs) if (e.getAction().contains(c.getRef())) l.add(e);
        return l;
    }

    // ---------------------------------------------------------------- budget maths (for the semester on screen)
    /** Cleared spending charged to this event. */
    static double spent(Event e) {
        double s = 0;
        for (Claim c : claims()) if (c.getEvent().equalsIgnoreCase(e.getName()) && c.getStatus() == ClaimStatus.CLEARED) s += c.getAmount();
        return s;
    }

    static double remaining(Event e) { return e.computeRemaining(spent(e)); }

    static double totalSpent() { double s = 0; for (Event e : events()) s += spent(e); return s; }

    static double available() { return budget().computeAvailableBalance(Store::spent); }

    static double pendingTotal() { double s = 0; for (Claim c : claims()) if (c.getStatus().isAwaiting()) s += c.getAmount(); return s; }

    static int pendingCount() { int n = 0; for (Claim c : claims()) if (c.getStatus().isAwaiting()) n++; return n; }

    static double actual(Event e) {
        double s = 0;
        for (Claim c : claims()) if (c.getEvent().equalsIgnoreCase(e.getName()) && c.getStatus().countsTowardActual()) s += c.getAmount();
        return s;
    }

    // ---------------------------------------------------------------- ids (unique across ALL semesters)
    static String nextRef() {
        int max = 0;
        for (Semester s : semesters) for (Claim c : s.getClaims()) max = Math.max(max, Integer.parseInt(c.getRef().substring(4)));
        return String.format("EXP-%04d", max + 1);
    }

    static String nextReportId() {
        int max = 0;
        for (Semester s : semesters) for (Report r : s.getReports()) max = Math.max(max, Integer.parseInt(r.getId().substring(3)));
        return String.format("LR-%03d", max + 1);
    }

    // ---------------------------------------------------------------- audit log
    static void log(String action) {
        logs.add(0, new LogEntry(LocalDateTime.now().format(TS), current == null ? "system" : current.signature(), action));
    }

    // ---------------------------------------------------------------- review workflow (business rules)
    static void approve(Claim c) {
        c.approve();
        log("Approved claim " + c.getRef() + " (" + peso(c.getAmount()) + ")");
    }

    static void flag(Claim c, String note) {
        c.flag(note);
        log("Flagged " + c.getRef() + ": " + note);
    }

    /** Moderator clearance. Returns an error message, or null on success. */
    static String clearClaim(Claim c) {
        if (!editable()) return "This semester is closed, so claims can no longer be cleared.";
        Event k = event(c.getEvent());
        if (k == null) return "Cannot clear: \"" + c.getEvent() + "\" has no budget allocation.";
        if (c.getAmount() > remaining(k))
            return "Cannot clear: amount exceeds the remaining allocation for " + k.getName() + " (" + peso(remaining(k)) + ").";
        c.clear();
        log("Granted clearance for " + c.getRef() + " (" + peso(c.getAmount()) + "), budget balance updated");
        return null;
    }

    // ---------------------------------------------------------------- demo seed data
    private static Claim mk(String ref, String desc, String by, double amt, String type, String date, String or,
                            String ev, ClaimStatus st, String disc) {
        return new Claim(ref, desc, by, amt, type, date, or, ev, st, disc);
    }

    static void seed() {
        String VE = "Venue & Equipment", FR = "Food & Refreshments", PM = "Printing & Materials",
                TR = "Transportation", HF = "Honoraria & Fees";

        // ---- a closed past semester, so the semester switcher has something to show
        Semester old = startSemester("A.Y. 2025-2026 - 2nd Sem", 420000);
        Budget ob = old.getBudget();
        ob.setContingency(40000);
        ob.addEvent(new Event("Intramurals", 60000));
        ob.addEvent(new Event("General Assembly", 25000));
        old.addClaim(mk("EXP-0071", "Referee Fees - Intramurals", "C. Refalbor", 12000, HF, "2025-03-10", "OR-2025-0201", "Intramurals", ClaimStatus.CLEARED, ""));
        old.addClaim(mk("EXP-0072", "Water & Snacks - Intramurals", "S. Remulta", 8400, FR, "2025-03-11", "OR-2025-0205", "Intramurals", ClaimStatus.CLEARED, ""));
        old.addClaim(mk("EXP-0073", "Meeting Snacks - General Assembly", "V. Po", 3200, FR, "2025-03-14", "OR-2025-0214", "General Assembly", ClaimStatus.CLEARED, ""));
        Report lr = new Report("LR-001", "Intramurals", 2, 20400, 60000, "F. Fernandes", "2025-03-20");
        lr.review("Prof. D. Santos");
        old.addReport(lr);
        logs.add(0, new LogEntry("2025-03-12 10:15:00", "Prof. D. Santos (Moderator)", "Granted clearance for EXP-0071"));
        logs.add(0, new LogEntry("2025-03-13 11:30:00", "Prof. D. Santos (Moderator)", "Granted clearance for EXP-0072"));
        logs.add(0, new LogEntry("2025-03-15 09:05:00", "Prof. D. Santos (Moderator)", "Granted clearance for EXP-0073"));

        // ---- the current semester (closes the one above)
        startSemester("A.Y. 2026-2027 - 1st Sem", 485000);
        Budget b = budget();
        b.setContingency(60000);
        b.addEvent(new Event("Acquaintance Party", 80000));
        b.addEvent(new Event("Foundation Week", 30000));
        b.addEvent(new Event("Leadership Training", 40000));
        addClaim(mk("EXP-0091", "Stage Rental - Acquaintance Party", "C. Refalbor", 28500, VE, "2025-09-02", "OR-2025-0455", "Acquaintance Party", ClaimStatus.CLEARED, ""));
        addClaim(mk("EXP-0090", "Catering - ACQ", "S. Remulta", 14200, FR, "2025-08-28", "OR-2025-0441", "Acquaintance Party", ClaimStatus.PENDING, ""));
        addClaim(mk("EXP-0089", "Printing - ACQ Pins (x12)", "V. Po", 6800, PM, "2025-08-25", "OR-2025-0430", "Acquaintance Party", ClaimStatus.CLEARED, ""));
        addClaim(mk("EXP-0088", "Sound System Rental - ACQ", "N. Mahipus", 18000, VE, "2025-08-20", "OR-2025-0412", "Acquaintance Party", ClaimStatus.FLAGGED, "Auditor note: receipt total differs from quotation"));
        String[] d = {"Transportation", "Snacks for Volunteers", "Certificates", "Venue Decorations", "Registration Supplies", "Banner Printing"};
        String[] t = {TR, FR, PM, VE, PM, PM};
        double[] a = {3200, 3800, 3500, 4000, 4500, 5000};
        for (int i = 0; i < 6; i++)
            addClaim(mk(String.format("EXP-%04d", 82 + i), d[i] + " - Foundation Week", "C. Refalbor", a[i],
                    t[i], "2025-08-1" + (i + 1), "OR-2025-03" + (10 + i), "Foundation Week", ClaimStatus.PENDING, ""));
        // oldest first, each inserted at the top, so LOG-0001 is the oldest and the newest shows first
        logs.add(0, new LogEntry("2025-08-21 16:05:37", "A. Reyes (Auditor)", "Flagged EXP-0088: receipt total differs from quotation"));
        logs.add(0, new LogEntry("2025-08-26 09:42:11", "A. Reyes (Auditor)", "Approved claim EXP-0089"));
        logs.add(0, new LogEntry("2025-09-02 14:10:00", "Prof. D. Santos (Moderator)", "Granted clearance for EXP-0091"));
    }
}
