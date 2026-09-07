package porto.sweep.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PipeSeq {
    private PipeSeq() {}

    public static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return out;
        }
        for (String part : trimmed.split(";")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public static Set<String> toSet(String value) {
        return new LinkedHashSet<>(split(value));
    }

    public static String join(List<String> values) {
        return String.join(";", values);
    }

    public static String joinSet(Set<String> values) {
        return String.join(";", values);
    }
}
