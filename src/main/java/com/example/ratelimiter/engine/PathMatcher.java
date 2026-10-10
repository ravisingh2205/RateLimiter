package com.example.ratelimiter.engine;

/** Minimal Ant-style matcher: "*" and "{var}" match one path segment, "**" matches the rest. */
public final class PathMatcher {

    private PathMatcher() {
    }

    public static boolean matches(String pattern, String path) {
        return match(split(pattern), 0, split(path), 0);
    }

    private static String[] split(String s) {
        String t = s.startsWith("/") ? s.substring(1) : s;
        if (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.isEmpty() ? new String[0] : t.split("/");
    }

    private static boolean match(String[] pat, int pi, String[] path, int xi) {
        while (pi < pat.length) {
            String p = pat[pi];
            if (p.equals("**")) {
                if (pi == pat.length - 1) {
                    return true;
                }
                for (int k = xi; k <= path.length; k++) {
                    if (match(pat, pi + 1, path, k)) {
                        return true;
                    }
                }
                return false;
            }
            if (xi >= path.length) {
                return false;
            }
            boolean segmentOk = p.equals("*") || (p.startsWith("{") && p.endsWith("}")) || p.equals(path[xi]);
            if (!segmentOk) {
                return false;
            }
            pi++;
            xi++;
        }
        return xi == path.length;
    }
}
