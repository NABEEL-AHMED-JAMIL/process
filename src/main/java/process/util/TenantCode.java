package process.util;

/**
 * A workspace's code -- the short name in its bucket, its topics and its URLs -- from whatever
 * name a person typed: lower case, letters, digits and single dashes, nothing at either end.
 * One rule, so a code made from a request and a code made by an admin come out the same.
 */
public final class TenantCode {

    private TenantCode() {}

    public static String from(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }
}
