package porto.sweep.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class SimpleCsv {
    private SimpleCsv() {}

    /**
     * Process a CSV one row at a time without retaining the entire file.
     * This is the preferred API for large simulation inputs.
     */
    public static void forEach(Path path, Consumer<Map<String, String>> consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return;
            }

            List<String> headers = parseCsvLine(stripBom(headerLine));
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> fields = parseCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>(Math.max(16, headers.size() * 2));
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < fields.size() ? fields.get(i) : "");
                }
                consumer.accept(row);
            }
        }
    }

    /**
     * Compatibility helper for genuinely small CSV files.
     * Large files should use {@link #forEach(Path, Consumer)}.
     */
    public static List<Map<String, String>> read(Path path) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        forEach(path, rows::add);
        return rows;
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    public static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        if (line == null) {
            return values;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }
}
