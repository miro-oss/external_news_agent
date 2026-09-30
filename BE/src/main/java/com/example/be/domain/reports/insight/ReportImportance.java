package com.example.be.domain.reports.insight;

/** Evidence limits the grade; missing values never become invented zeroes. */
public final class ReportImportance {
    private ReportImportance() { }

    public static String grade(Integer directness, Integer impact, Integer urgency) {
        if (directness == null || impact == null) return "unavailable";
        if (directness == 0) return "low";
        double score = score(directness, impact, urgency);
        return score >= 2.25 ? "high" : score >= 1.25 ? "medium" : "low";
    }

    public static double score(Integer directness, Integer impact, Integer urgency) {
        if (directness == null || impact == null) return -1;
        if (directness == 0) return 0;
        return (directness * .4 + impact * .4 + (urgency == null ? 0 : urgency * .2))
                / (urgency == null ? .8 : 1);
    }

    public static int priority(String grade) {
        return switch (grade) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }
}
